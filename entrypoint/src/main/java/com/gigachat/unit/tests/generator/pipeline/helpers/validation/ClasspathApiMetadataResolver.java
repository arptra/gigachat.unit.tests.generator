package com.gigachat.unit.tests.generator.pipeline.helpers.validation;

import com.gigachat.unit.tests.generator.analyzer.ConstructorMetadata;
import com.gigachat.unit.tests.generator.analyzer.MethodSignatureRegistry;
import com.gigachat.unit.tests.generator.analyzer.ParameterMetadata;
import com.gigachat.unit.tests.generator.gradle.ResolvedTestRuntimeClasspath;
import com.gigachat.unit.tests.generator.gradle.TestRuntimeClasspathResolver;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Loads public API metadata for classes that come from the Gradle test runtime classpath rather
 * than from scanned source files.
 */
final class ClasspathApiMetadataResolver {

    private final PipelineLogger logger;
    private final MethodSignatureRegistry signatureRegistry;
    private final TestRuntimeClasspathResolver classpathResolver;
    private final ConcurrentMap<String, URLClassLoader> classLoaders = new ConcurrentHashMap<>();
    private final Set<String> registeredTypes = ConcurrentHashMap.newKeySet();

    ClasspathApiMetadataResolver(PipelineLogger logger, MethodSignatureRegistry signatureRegistry) {
        this(logger, signatureRegistry, new TestRuntimeClasspathResolver(logger));
    }

    ClasspathApiMetadataResolver(PipelineLogger logger,
                                 MethodSignatureRegistry signatureRegistry,
                                 TestRuntimeClasspathResolver classpathResolver) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.signatureRegistry = Objects.requireNonNull(signatureRegistry, "signatureRegistry");
        this.classpathResolver = Objects.requireNonNull(classpathResolver, "classpathResolver");
    }

    boolean registerType(Path projectRoot, Path testClassFile, List<String> candidateNames) {
        if (candidateNames == null || candidateNames.isEmpty()) {
            return false;
        }
        for (String candidateName : candidateNames) {
            if (registerType(projectRoot, testClassFile, candidateName)) {
                return true;
            }
        }
        return false;
    }

    boolean registerType(Path projectRoot, Path testClassFile, String candidateName) {
        if (projectRoot == null || testClassFile == null || candidateName == null || candidateName.isBlank()) {
            return false;
        }
        for (String loadName : binaryNameCandidates(candidateName)) {
            String cacheKey = cacheKey(projectRoot, testClassFile, loadName);
            if (registeredTypes.contains(cacheKey)) {
                return true;
            }
            Class<?> clazz = loadClass(projectRoot, testClassFile, loadName);
            if (clazz == null) {
                continue;
            }
            registerPublicApi(clazz);
            registeredTypes.add(cacheKey);
            return true;
        }
        return false;
    }

    private Class<?> loadClass(Path projectRoot, Path testClassFile, String className) {
        try {
            URLClassLoader loader = classLoaders.computeIfAbsent(loaderKey(projectRoot, testClassFile),
                    ignored -> buildClassLoader(projectRoot, testClassFile));
            return Class.forName(className, false, loader);
        } catch (ClassNotFoundException | LinkageError exception) {
            return null;
        }
    }

    private String cacheKey(Path projectRoot, Path testClassFile, String className) {
        return projectRoot.toAbsolutePath().normalize()
                + "|" + testClassFile.toAbsolutePath().normalize()
                + "|" + className;
    }

    private URLClassLoader buildClassLoader(Path projectRoot, Path testClassFile) {
        ResolvedTestRuntimeClasspath classpath = classpathResolver.resolve(projectRoot, testClassFile);
        List<URL> urls = new ArrayList<>();
        for (Path entry : classpath.entries()) {
            if (entry == null || !Files.exists(entry)) {
                continue;
            }
            try {
                urls.add(entry.toUri().toURL());
            } catch (Exception ignored) {
                // Ignore malformed classpath entries and keep trying the rest.
            }
        }
        ClassLoader parent = Thread.currentThread().getContextClassLoader();
        if (parent == null) {
            parent = ClasspathApiMetadataResolver.class.getClassLoader();
        }
        return new URLClassLoader(urls.toArray(URL[]::new), parent);
    }

    private String loaderKey(Path projectRoot, Path testClassFile) {
        return projectRoot.toAbsolutePath().normalize()
                + "|"
                + testClassFile.toAbsolutePath().normalize();
    }

    private void registerPublicApi(Class<?> clazz) {
        if (clazz == null) {
            return;
        }
        String simpleName = clazz.getSimpleName();
        if (simpleName == null || simpleName.isBlank()) {
            return;
        }
        int constructorCount = 0;
        for (Constructor<?> constructor : clazz.getConstructors()) {
            if (constructor == null || !Modifier.isPublic(constructor.getModifiers())) {
                continue;
            }
            signatureRegistry.registerConstructor(simpleName, toConstructorMetadata(simpleName, constructor));
            constructorCount++;
        }
        int methodCount = 0;
        for (Method method : clazz.getMethods()) {
            if (method == null || !Modifier.isPublic(method.getModifiers()) || Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            signatureRegistry.registerMethod(simpleName, toMethodSignature(method));
            methodCount++;
        }
        if (constructorCount > 0 || methodCount > 0) {
            logger.info("Registered classpath API metadata for " + clazz.getName()
                    + ": constructors=" + constructorCount + ", methods=" + methodCount);
        }
    }

    private ConstructorMetadata toConstructorMetadata(String simpleName, Constructor<?> constructor) {
        List<ParameterMetadata> parameters = new ArrayList<>();
        Parameter[] reflectionParameters = constructor.getParameters();
        Class<?>[] parameterTypes = constructor.getParameterTypes();
        for (int index = 0; index < parameterTypes.length; index++) {
            String parameterName = index < reflectionParameters.length
                    ? reflectionParameters[index].getName()
                    : "arg" + index;
            parameters.add(new ParameterMetadata(parameterName, simpleName(parameterTypes[index]), List.of()));
        }
        return new ConstructorMetadata(simpleName + formatParameters(parameters), parameters);
    }

    private String toMethodSignature(Method method) {
        List<String> parameters = new ArrayList<>();
        Parameter[] reflectionParameters = method.getParameters();
        Class<?>[] parameterTypes = method.getParameterTypes();
        for (int index = 0; index < parameterTypes.length; index++) {
            String parameterName = index < reflectionParameters.length
                    ? reflectionParameters[index].getName()
                    : "arg" + index;
            parameters.add(simpleName(parameterTypes[index]) + " " + parameterName);
        }
        return simpleName(method.getReturnType()) + " "
                + method.getName()
                + "(" + String.join(", ", parameters) + ")";
    }

    private String formatParameters(List<ParameterMetadata> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return "()";
        }
        List<String> parts = new ArrayList<>(parameters.size());
        for (ParameterMetadata parameter : parameters) {
            parts.add(parameter.type() + " " + parameter.name());
        }
        return "(" + String.join(", ", parts) + ")";
    }

    private String simpleName(Class<?> clazz) {
        if (clazz == null) {
            return "Object";
        }
        if (clazz.isArray()) {
            return simpleName(clazz.getComponentType()) + "[]";
        }
        String simpleName = clazz.getSimpleName();
        return simpleName == null || simpleName.isBlank() ? clazz.getName() : simpleName;
    }

    private List<String> binaryNameCandidates(String className) {
        String value = className == null ? "" : className.trim();
        if (value.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        candidates.add(value);
        int dot = value.lastIndexOf('.');
        while (dot > 0) {
            value = value.substring(0, dot) + "$" + value.substring(dot + 1);
            candidates.add(value);
            dot = value.lastIndexOf('.', dot - 1);
        }
        return List.copyOf(candidates);
    }
}

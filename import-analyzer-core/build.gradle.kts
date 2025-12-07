plugins {
    `java-library`
}

group = "com.example.importanalyzer"
version = "0.1.0"

dependencies {
    api("com.github.javaparser:javaparser-core:3.25.9")
    api("com.fasterxml.jackson.core:jackson-databind:2.17.1")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.1")
    implementation("org.slf4j:slf4j-api:2.0.13")
    implementation("org.gradle:gradle-tooling-api:8.4")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

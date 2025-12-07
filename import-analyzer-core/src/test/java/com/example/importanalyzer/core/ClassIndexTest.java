package com.example.importanalyzer.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ClassIndexTest {
    @Test
    void registersAndQueriesEntries() {
        ClassIndex index = new ClassIndex();
        ClassIndexEntry entry = new ClassIndexEntry("com.example.Foo", "Foo", ClassOrigin.PROJECT_MAIN, Path.of("Foo.java"));
        index.addEntry(entry);
        assertEquals(entry, index.getByFqn("com.example.Foo"));
        assertFalse(index.bySimpleName("Foo").isEmpty());
        assertFalse(index.byPackage("com.example").isEmpty());
    }
}

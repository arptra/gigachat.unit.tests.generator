package com.example.lib;

public class LibraryComponent {

    public void load() {
        System.out.println("Library component loaded");
    }

    public void reload() {
        System.out.println("Library component reloaded");
    }

    public void close() {
        System.out.println("Library component closed");
    }

    public boolean status() {
        return true;
    }
}

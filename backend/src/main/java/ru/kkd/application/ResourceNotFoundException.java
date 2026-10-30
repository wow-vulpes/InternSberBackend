package ru.kkd.application;

public class ResourceNotFoundException extends RuntimeException {

    private final String resource;

    public ResourceNotFoundException(String resource, String id) {
        super(resource + " not found: " + id);
        this.resource = resource;
    }

    public String resource() {
        return resource;
    }
}

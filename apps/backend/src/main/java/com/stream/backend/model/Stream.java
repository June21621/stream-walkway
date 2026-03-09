package com.stream.backend.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class Stream {

    private Long id;
    private String name;
    private String location;

    @JsonProperty("created_at")
    private String createdAt;

    public Stream() {}

    public Stream(Long id, String name, String location, String createdAt) {
        this.id = id;
        this.name = name;
        this.location = location;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}

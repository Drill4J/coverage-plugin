package com.epam.drill.plugins.test2code.ast;


import java.util.UUID;

public class ConstructorTestBuild2 {
    private String id;
    private String email;
    private String username;
    private String password;
    private String bio;
    private String image;
    private String phone;

    public ConstructorTestBuild2(String email, String username, String password, String bio, String image) {
        this.id = UUID.randomUUID().toString();
        this.email = email;
        this.username = username;
        this.password = password;
        this.bio = bio;
        this.image = image;
        this.phone = "";
    }

    public ConstructorTestBuild2() {

    }
}

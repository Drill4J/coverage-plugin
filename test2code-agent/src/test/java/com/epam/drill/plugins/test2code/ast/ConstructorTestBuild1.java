package com.epam.drill.plugins.test2code.ast;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Getter
@NoArgsConstructor
@EqualsAndHashCode(of = {"id"})
public class ConstructorTestBuild1 {
    private String id;
    private String email;
    private String username;
    private String password;
    private String bio;
    private String image;

    public ConstructorTestBuild1(String email, String username, String password, String bio, String image) {
        this.id = UUID.randomUUID().toString();
        this.email = email;
        this.username = username;
        this.password = password;
        this.bio = bio;
        this.image = image;
    }

}

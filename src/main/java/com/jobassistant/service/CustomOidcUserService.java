package com.jobassistant.service;

import com.jobassistant.entity.User;
import com.jobassistant.repository.UserRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@Transactional
@RequiredArgsConstructor
public class CustomOidcUserService extends OidcUserService {

    private final UserRepository userRepository;

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {

        OidcUser oidcUser = super.loadUser(userRequest);

        String googleId = oidcUser.getAttribute("sub");
        String email = oidcUser.getAttribute("email");
        String name = oidcUser.getAttribute("name");
        String picture = oidcUser.getAttribute("picture");

        log.atInfo()
                .addKeyValue("googleId", googleId)
                .addKeyValue("email", email)
                .addKeyValue("name", name)
                .log("User loaded");

        User user = userRepository.findByGoogleId(googleId)
                .map(existing -> updateExistingUser(existing, name, picture))
                .orElseGet(() -> createNewUser(googleId, email, name, picture));

        return oidcUser;
    }

    private User updateExistingUser(User user, String name, String picture) {
        user.setName(name);
        user.setPicture(picture);
        return userRepository.save(user);
    }

    private User createNewUser(String googleId, String email, String name, String picture) {
        User newUser = User.builder()
                .googleId(googleId)
                .email(email)
                .name(name)
                .picture(picture)
                .build();

        return userRepository.save(newUser);
    }

}

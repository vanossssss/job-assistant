package com.jobassistant.utils;

import com.jobassistant.entity.User;
import com.jobassistant.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AuthUtils {

    private final UserRepository userRepository;

    public User getCurrentUser() {
        OAuth2User oAuth2User = (OAuth2User) SecurityContextHolder
                .getContext()
                .getAuthentication()
                .getPrincipal();

        String googleId = oAuth2User.getAttribute("sub");

        return userRepository.findByGoogleId(googleId)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }
}

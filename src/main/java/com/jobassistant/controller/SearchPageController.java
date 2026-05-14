package com.jobassistant.controller;

import com.jobassistant.entity.User;
import com.jobassistant.utils.AuthUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@RequiredArgsConstructor
public class SearchPageController {

    private final AuthUtils authUtils;

    @GetMapping("/search")
    public String viewSearchPage(Model model) {
        User currentUser = authUtils.getCurrentUser();
        model.addAttribute("user", currentUser);

        return "search";
    }
}

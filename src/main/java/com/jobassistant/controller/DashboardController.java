package com.jobassistant.controller;

import com.jobassistant.entity.User;
import com.jobassistant.utils.AuthUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@RequiredArgsConstructor
public class DashboardController {

    private final AuthUtils authUtils;

    @GetMapping("/dashboard")
    public String viewDashboard(Model model){
        User currentUser = authUtils.getCurrentUser();
        model.addAttribute("user", currentUser);
        return "dashboard";
    }
}

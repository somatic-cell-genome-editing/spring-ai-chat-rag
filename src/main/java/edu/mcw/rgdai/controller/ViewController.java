package edu.mcw.rgdai.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
@Controller
public class ViewController {
    private static final Logger logger = LoggerFactory.getLogger(ViewController.class);

    @GetMapping(value = {"", "/", "/home"})
    public String home() {
        logger.info("Accessing home page");
//        return "redirect:/login";
        return  "redirect:/chat";
    }

    @GetMapping("/login")
    public String login() {
        logger.info("Accessing login page");
        return "login";
    }

    @GetMapping("/chat")
    public String chat() {
        logger.info("Accessing chat page");
        return "chat-openai";
    }

    @GetMapping("/chat-openai")
    public String chatOpenAi() {
        logger.info("Accessing chat page");
        return "chat-openai";
    }

    @GetMapping("/chat-ollama")
    public String chatOllama() {
        logger.info("Accessing chat page");
        return "chat";
    }

}
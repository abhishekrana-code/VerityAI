package com.verityai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.session.SessionAutoConfiguration;
import org.springframework.session.jdbc.config.annotation.web.http.EnableJdbcHttpSession;

@SpringBootApplication(exclude = {SessionAutoConfiguration.class})
@EnableJdbcHttpSession
public class VerityAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(VerityAiApplication.class, args);
    }
}
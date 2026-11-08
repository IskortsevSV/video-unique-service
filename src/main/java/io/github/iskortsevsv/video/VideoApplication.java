package io.github.iskortsevsv.video;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Точка входа приложения и регистрации настроек Spring. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class VideoApplication {
    /** Запускает приложение Spring Boot. */
    public static void main(String[] args) {
        SpringApplication.run(VideoApplication.class, args);
    }
}

package io.github.iskortsevsv.video;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import io.github.iskortsevsv.video.service.VideoProcessor;

class ApiTest {
    static ConfigurableApplicationContext context;
    static VideoProcessor processor = mock(VideoProcessor.class);

    @Configuration(proxyBeanMethods = false)
    static class TestConfig {
        @Bean
        @Primary
        VideoProcessor testProcessor() {
            return processor;
        }
    }

    // Spring 7's Jupiter extension targets JUnit 6. Start explicitly to retain JUnit 5.
    @BeforeAll
    static void start() {
        context =
                new SpringApplicationBuilder(VideoApplication.class, TestConfig.class)
                        .properties("server.port=0")
                        .run();
    }

    @AfterAll
    static void stop() {
        if (context != null) {
            context.close();
        }
    }

    @BeforeEach
    void resetProcessor() {
        reset(processor);
    }

    MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup((WebApplicationContext) context).build();
    }

    @Test
    void downloadsWithHashesAndDefaults() throws Exception {
        doAnswer(
                        call -> {
                            Files.writeString(call.getArgument(1), "output");
                            return null;
                        })
                .when(processor)
                .process(any(), any(), any());
        mvc().perform(
                        multipart("/api/videos/unique")
                                .file(
                                        new MockMultipartFile(
                                                "file",
                                                "video.mp4",
                                                "video/mp4",
                                                "input".getBytes())))
                .andExpect(status().isOk())
                .andExpect(content().contentType("video/mp4"))
                .andExpect(
                        header().string(
                                        "X-Hash-Before",
                                        org.hamcrest.Matchers.matchesPattern("[0-9a-f]{64}")))
                .andExpect(
                        header().string(
                                        "X-Hash-After",
                                        org.hamcrest.Matchers.matchesPattern("[0-9a-f]{64}")))
                .andExpect(content().string("output"));
        verify(processor)
                .process(
                        any(),
                        any(),
                        eq(new io.github.iskortsevsv.video.dto.ProcessingOptions(1.01, .98)));
    }

    @Test
    void rejectsMissingFileAndInvalidNumbers() throws Exception {
        mvc().perform(multipart("/api/videos/unique")).andExpect(status().isBadRequest());
        for (String speed : new String[] {"NaN", "Infinity", "bad", "0"}) {
            mvc().perform(
                            multipart("/api/videos/unique")
                                    .file(
                                            new MockMultipartFile(
                                                    "file",
                                                    "video.mp4",
                                                    "video/mp4",
                                                    new byte[] {1}))
                                    .param("speed", speed))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void exposesOpenApiAndSwagger() throws Exception {
        mvc().perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/videos/unique'].post").exists());
        mvc().perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }
}

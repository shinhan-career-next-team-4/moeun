package com.moeun.backend.plan;

import java.io.IOException;
import java.io.UncheckedIOException;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import com.moeun.backend.plan.application.PlanRules;

/** 단위 테스트용: 실제 rules.yml을 스프링 컨텍스트 없이 읽는다. */
public final class PlanRulesFixture {

    private PlanRulesFixture() {
    }

    public static PlanRules load() {
        try {
            var sources = new YamlPropertySourceLoader().load("rules", new ClassPathResource("config/rules.yml"));
            return new Binder(ConfigurationPropertySources.from(sources)).bind("moeun.rules", PlanRules.class).get();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

package com.moeun.backend.config.copy;

import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 서버가 뜰 때 문구표를 읽는다. 파일이 없거나 키가 빠졌으면 기동이 실패한다(CP-05).
 */
@Configuration(proxyBeanMethods = false)
class CopyConfig {

    /**
     * 작업 디렉터리(backend/) 기준 저장소 루트 copy/. 배포에서 파일 위치가 다르면 moeun.copy.path로 바꾼다.
     * Path로 바로 받으면 서블릿 웹 컨텍스트에서 PathEditor가 상대 경로를 서블릿 리소스로 해석해
     * Tomcat이 "/../copy/..."를 거부하므로, 문자열로 받아 파일 시스템 경로로 직접 바꾼다.
     */
    @Bean
    CopyBook copyBook(@Value("${moeun.copy.path:../copy/copy.ko.yml}") String path) {
        return CopyBook.load(Path.of(path));
    }
}

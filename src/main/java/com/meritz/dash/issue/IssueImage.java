package com.meritz.dash.issue;

/**
 * 스크린샷 원본 응답 DTO. 매퍼 내부 타입({@code IssueMapper.ImageData})이 컨트롤러로
 * 누출되지 않도록 서비스 경계에서 감싼다. {@code contentType} 은 업로드 시 매직넘버로
 * 감지해 정규화한 값(image/png 등)이다.
 */
public record IssueImage(byte[] data, String contentType) {}

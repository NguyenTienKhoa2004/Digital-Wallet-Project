package com.flowpay.common.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

public class ApiException extends RuntimeException {

	private final HttpStatus status;
	private final String code;
	private final String reference;

	public ApiException(HttpStatus status, String code, String message) {
		this(status, code, message, null);
	}

	public ApiException(HttpStatus status, String code, String message, String reference) {
		super(message);
		this.status = status;
		this.code = code;
		this.reference = reference;
	}

	public HttpStatus getStatus() {
		return status;
	}

	public String getCode() {
		return code;
	}

	public String getReference() {
		return reference;
	}

	public ProblemDetail toProblemDetail() {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, getMessage());
		problem.setProperty("code", code);
		if (reference != null) {
			problem.setProperty("reference", reference);
		}
		return problem;
	}

}

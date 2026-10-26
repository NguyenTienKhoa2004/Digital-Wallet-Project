package com.flowpay.common.exception;

import org.springframework.http.ProblemDetail;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

@RestControllerAdvice
public class ApiExceptionHandler {

	@ExceptionHandler(ApiException.class)
	public ProblemDetail handleApiException(ApiException exception) {
		return exception.toProblemDetail();
	}

	@ExceptionHandler(MissingRequestHeaderException.class)
	public ProblemDetail handleMissingHeader(MissingRequestHeaderException exception) {
		return new ApiException(HttpStatus.BAD_REQUEST, "MISSING_REQUIRED_HEADER",
				"Required header is missing: " + exception.getHeaderName()).toProblemDetail();
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ProblemDetail handleInvalidParameter(MethodArgumentTypeMismatchException exception) {
		return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER",
				"Invalid query parameter: " + exception.getName()).toProblemDetail();
	}

	@ExceptionHandler({DataAccessException.class, TransactionException.class})
	public ProblemDetail handleUnconfirmedOutcome(RuntimeException exception) {
		// A DB/commit error does not prove that a financial operation failed.
		return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "OPERATION_OUTCOME_UNKNOWN",
				"The outcome could not be confirmed. Look up the result or retry with the same Idempotency-Key.")
				.toProblemDetail();
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ProblemDetail handleValidation(MethodArgumentNotValidException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed.");
		problem.setProperty("code", "VALIDATION_FAILED");
		problem.setProperty("errors", exception.getBindingResult().getFieldErrors().stream()
				.map(error -> java.util.Map.of("field", error.getField(), "message",
						error.getDefaultMessage() == null ? "Invalid value" : error.getDefaultMessage()))
				.toList());
		return problem;
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ProblemDetail handleInvalidJson(HttpMessageNotReadableException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "A valid JSON request body is required.");
		problem.setProperty("code", "INVALID_REQUEST_BODY");
		return problem;
	}

}

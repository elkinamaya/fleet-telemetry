package com.fleet.telemetry.infrastructure.adapter.in.web;

import com.fleet.telemetry.application.usecase.VehicleTelemetryNotFoundException;
import com.fleet.telemetry.infrastructure.adapter.out.kafka.TelemetryPublicationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.util.stream.Collectors;

@RestControllerAdvice
public class TelemetryExceptionHandler {

	private static final Logger logger = LoggerFactory.getLogger(TelemetryExceptionHandler.class);

	@ExceptionHandler(AsyncRequestNotUsableException.class)
	public void handleUnusableAsyncResponse(
			AsyncRequestNotUsableException exception,
			HttpServletRequest request,
			HttpServletResponse response) {
		logger.debug("Async response is no longer usable for {}", request.getRequestURI());
	}

	@ExceptionHandler(TelemetryPublicationException.class)
	public ResponseEntity<ProblemDetail> handlePublicationFailure(
			TelemetryPublicationException exception,
			HttpServletRequest request) {
		logger.warn("Kafka did not confirm telemetry publication for {}", request.getRequestURI(), exception);
		return problem(
				HttpStatus.SERVICE_UNAVAILABLE,
				"TELEMETRY_PUBLICATION_UNAVAILABLE",
				"No fue posible confirmar la publicación de la telemetría. Intente nuevamente.",
				request);
	}

	@ExceptionHandler(VehicleTelemetryNotFoundException.class)
	public ResponseEntity<ProblemDetail> handleVehicleTelemetryNotFound(
			VehicleTelemetryNotFoundException exception,
			HttpServletRequest request) {
		return problem(
				HttpStatus.NOT_FOUND,
				"VEHICLE_TELEMETRY_NOT_FOUND",
				"No hay telemetría registrada para el vehículo '" + exception.getVehicleId() + "'.",
				request);
	}

	@ExceptionHandler({
			MethodArgumentNotValidException.class,
			HandlerMethodValidationException.class,
			ConstraintViolationException.class
	})
	public ResponseEntity<ProblemDetail> handleValidationFailure(
			Exception exception,
			HttpServletRequest request) {
		String fields = exception instanceof MethodArgumentNotValidException validationException
				? validationException.getBindingResult().getFieldErrors().stream()
						.map(error -> error.getField())
						.distinct()
						.sorted()
						.collect(Collectors.joining(", "))
				: "";
		String detail = fields.isEmpty()
				? "La solicitud contiene uno o más valores inválidos."
				: "Revise los siguientes campos: " + fields + ".";
		return problem(HttpStatus.BAD_REQUEST, "REQUEST_VALIDATION_FAILED", detail, request);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ProblemDetail> handleUnreadableRequest(
			HttpMessageNotReadableException exception,
			HttpServletRequest request) {
		return problem(
				HttpStatus.BAD_REQUEST,
				"REQUEST_BODY_INVALID",
				"El cuerpo de la solicitud está vacío o contiene JSON inválido.",
				request);
	}

	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	public ResponseEntity<ProblemDetail> handleMethodNotAllowed(
			HttpRequestMethodNotSupportedException exception,
			HttpServletRequest request) {
		return problem(
				HttpStatus.METHOD_NOT_ALLOWED,
				"HTTP_METHOD_NOT_ALLOWED",
				"El método HTTP no está permitido para esta ruta.",
				request);
	}

	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	public ResponseEntity<ProblemDetail> handleUnsupportedMediaType(
			HttpMediaTypeNotSupportedException exception,
			HttpServletRequest request) {
		return problem(
				HttpStatus.UNSUPPORTED_MEDIA_TYPE,
				"UNSUPPORTED_MEDIA_TYPE",
				"El tipo de contenido de la solicitud no está soportado.",
				request);
	}

	@ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
	public ResponseEntity<ProblemDetail> handleRouteNotFound(
			Exception exception,
			HttpServletRequest request) {
		return problem(
				HttpStatus.NOT_FOUND,
				"ROUTE_NOT_FOUND",
				"No se encontró la ruta solicitada.",
				request);
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ProblemDetail> handleUnexpectedFailure(
			Exception exception,
			HttpServletRequest request) {
		logger.error("Unexpected HTTP failure for {}", request.getRequestURI(), exception);
		return problem(
				HttpStatus.INTERNAL_SERVER_ERROR,
				"INTERNAL_SERVER_ERROR",
				"Ocurrió un error interno al procesar la solicitud.",
				request);
	}

	private ResponseEntity<ProblemDetail> problem(
			HttpStatus status,
			String code,
			String detail,
			HttpServletRequest request) {
		ProblemDetail problem = ProblemDetail.forStatus(status);
		problem.setType(URI.create("about:blank"));
		problem.setTitle(title(status));
		problem.setDetail(detail);
		problem.setInstance(URI.create(request.getRequestURI()));
		problem.setProperty("code", code);

		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
		return new ResponseEntity<>(problem, headers, status);
	}

	private String title(HttpStatus status) {
		return switch (status) {
			case BAD_REQUEST -> "Solicitud inválida";
			case NOT_FOUND -> "Recurso no encontrado";
			case METHOD_NOT_ALLOWED -> "Método no permitido";
			case UNSUPPORTED_MEDIA_TYPE -> "Tipo de contenido no soportado";
			case SERVICE_UNAVAILABLE -> "Servicio temporalmente no disponible";
			default -> "Error interno del servidor";
		};
	}
}

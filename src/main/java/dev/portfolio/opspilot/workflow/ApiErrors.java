package dev.portfolio.opspilot.workflow;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(NoSuchElementException.class) public ResponseEntity<?> missing(NoSuchElementException e) { return ResponseEntity.status(404).body(Map.of("error", e.getMessage())); }
    @ExceptionHandler(IllegalStateException.class) public ResponseEntity<?> conflict(IllegalStateException e) { return ResponseEntity.status(409).body(Map.of("error", e.getMessage())); }
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<?> bad(IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class}) public ResponseEntity<?> validation(Exception e) { return ResponseEntity.badRequest().body(Map.of("error", "请求参数无效，请检查模式、场景和输入长度")); }
    @ExceptionHandler(Exception.class) public ResponseEntity<?> unknown(Exception e) { return ResponseEntity.internalServerError().body(Map.of("error", "服务暂时无法完成请求，请查看服务日志")); }
}

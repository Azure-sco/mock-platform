package com.xuntian.mock.control.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.xuntian.mock.common.ApiResponse;
import com.xuntian.mock.control.identity.OperatorContext;
import com.xuntian.mock.control.identity.OperatorGuard;
import com.xuntian.mock.control.web.PlatformController;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/admin/v1")
public final class ContractAdminController {

    private final ContractService contractService;
    private final ContractImportService contractImportService;
    private final OperatorGuard operatorGuard;

    public ContractAdminController(
            ContractService contractService,
            ContractImportService contractImportService,
            OperatorGuard operatorGuard) {
        this.contractService = contractService;
        this.contractImportService = contractImportService;
        this.operatorGuard = operatorGuard;
    }

    @PostMapping(
            value = "/apis/{apiId}/contracts/import",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ContractService.ContractView> importContract(
            @PathVariable long apiId,
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String path,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) String target,
            @RequestHeader(value = "Content-Encoding", required = false) String contentEncoding,
            HttpServletRequest request) throws java.io.IOException {
        OperatorContext operator = operatorGuard.requireAnyRole("MOCK_ADMIN");
        if (contentEncoding != null && !contentEncoding.isBlank()
                && !"identity".equalsIgnoreCase(contentEncoding.trim())) {
            throw new com.xuntian.mock.common.PlatformException(
                    com.xuntian.mock.common.ErrorCode.INVALID_REQUEST,
                    "Content-Encoding is not allowed for contract import");
        }
        if (file.getSize() > ContractImportService.MAX_FILE_BYTES) {
            throw new com.xuntian.mock.common.PlatformException(
                    com.xuntian.mock.common.ErrorCode.PAYLOAD_TOO_LARGE,
                    "Contract file exceeds 5 MB");
        }
        ContractImportService.ParsedContract parsed = contractImportService.parse(
                file.getBytes(),
                file.getOriginalFilename(),
                new ContractImportService.ImportOptions(path, method, target));
        ContractService.ContractView created = contractService.create(
                apiId,
                new ContractService.CreateCommand(
                        parsed.requestSchema(),
                        parsed.responseSchema(),
                        parsed.examples(),
                        null,
                        null,
                        null,
                        parsed.sourceType(),
                        parsed.sourceFileHash()),
                operator);
        return ApiResponse.success(created, PlatformController.requestId(request));
    }

    @GetMapping("/apis/{apiId}/contracts")
    public ApiResponse<List<ContractService.ContractView>> findByApi(
            @PathVariable long apiId,
            HttpServletRequest request) {
        operatorGuard.requireAnyRole("MOCK_VIEWER", "MOCK_ADMIN");
        return ApiResponse.success(contractService.findByApi(apiId), PlatformController.requestId(request));
    }

    @PostMapping("/apis/{apiId}/contracts")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ContractService.ContractView> create(
            @PathVariable long apiId,
            @RequestBody CreateRequest body,
            HttpServletRequest request) {
        OperatorContext operator = operatorGuard.requireAnyRole("MOCK_ADMIN");
        ContractService.ContractView created = contractService.create(
                apiId,
                new ContractService.CreateCommand(
                        body.requestSchema(),
                        body.responseSchema(),
                        body.examples(),
                        body.errorCodes(),
                        body.businessKeyExtractor(),
                        body.signatureMetadata(),
                        body.sourceType(),
                        body.sourceFileHash()),
                operator);
        return ApiResponse.success(created, PlatformController.requestId(request));
    }

    @PostMapping("/contracts/{id}/validate")
    public ApiResponse<ContractService.ContractView> validate(
            @PathVariable long id,
            HttpServletRequest request) {
        OperatorContext operator = operatorGuard.requireAnyRole("MOCK_ADMIN");
        return ApiResponse.success(contractService.validate(id, operator), PlatformController.requestId(request));
    }

    @PostMapping("/contracts/{id}/publish")
    public ApiResponse<ContractService.ContractView> publish(
            @PathVariable long id,
            HttpServletRequest request) {
        OperatorContext operator = operatorGuard.requireAnyRole("MOCK_ADMIN");
        return ApiResponse.success(contractService.publish(id, operator), PlatformController.requestId(request));
    }

    @GetMapping("/contracts/{id}/diff")
    public ApiResponse<ContractService.DiffResult> diff(
            @PathVariable long id,
            @RequestParam long compareTo,
            HttpServletRequest request) {
        operatorGuard.requireAnyRole("MOCK_VIEWER", "MOCK_ADMIN");
        return ApiResponse.success(contractService.diff(id, compareTo), PlatformController.requestId(request));
    }

    public record CreateRequest(
            JsonNode requestSchema,
            JsonNode responseSchema,
            JsonNode examples,
            JsonNode errorCodes,
            JsonNode businessKeyExtractor,
            JsonNode signatureMetadata,
            String sourceType,
            String sourceFileHash) {
    }
}

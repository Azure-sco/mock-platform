CREATE TABLE mock_scenario (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    scenario_code VARCHAR(64) NOT NULL,
    scenario_name VARCHAR(128) NOT NULL,
    provider_id BIGINT NOT NULL,
    api_id BIGINT NOT NULL,
    current_draft_version INT NULL,
    status VARCHAR(32) NOT NULL,
    created_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_by VARCHAR(128) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_scenario_code (scenario_code),
    INDEX idx_scenario_api_status (api_id, status),
    CONSTRAINT fk_scenario_provider FOREIGN KEY (provider_id) REFERENCES mock_provider (id),
    CONSTRAINT fk_scenario_api FOREIGN KEY (api_id) REFERENCES mock_api (id),
    CONSTRAINT chk_scenario_status CHECK (status IN ('ENABLED', 'DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE mock_approval_request (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    object_type VARCHAR(64) NOT NULL,
    object_id BIGINT NOT NULL,
    object_checksum CHAR(64) NOT NULL,
    policy_code VARCHAR(64) NOT NULL,
    required_count TINYINT UNSIGNED NOT NULL,
    status VARCHAR(32) NOT NULL,
    requested_by VARCHAR(128) NOT NULL,
    requested_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at TIMESTAMP(6) NULL,
    INDEX idx_approval_object_status (object_type, object_id, status),
    UNIQUE KEY uk_approval_object_checksum_policy (
        object_type, object_id, object_checksum, policy_code
    ),
    CONSTRAINT chk_approval_required_count CHECK (required_count BETWEEN 1 AND 2),
    CONSTRAINT chk_approval_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE mock_approval_decision (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    approval_request_id BIGINT NOT NULL,
    reviewer VARCHAR(128) NOT NULL,
    decision VARCHAR(32) NOT NULL,
    comment VARCHAR(512) NULL,
    decided_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_approval_reviewer (approval_request_id, reviewer),
    INDEX idx_approval_decision (approval_request_id, decision),
    CONSTRAINT fk_approval_decision_request
        FOREIGN KEY (approval_request_id) REFERENCES mock_approval_request (id),
    CONSTRAINT chk_approval_decision CHECK (decision IN ('APPROVE', 'REJECT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE mock_scenario_version (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    scenario_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    contract_version_id BIGINT NOT NULL,
    flow_definition_version_id BIGINT NULL,
    priority INT NOT NULL,
    effective_from TIMESTAMP(6) NULL,
    effective_to TIMESTAMP(6) NULL,
    scope_json JSON NOT NULL,
    match_rule_json JSON NOT NULL,
    response_json JSON NOT NULL,
    callback_json JSON NOT NULL,
    compiled_json JSON NULL,
    checksum CHAR(64) NOT NULL,
    validation_status VARCHAR(32) NOT NULL,
    validation_result_json JSON NULL,
    approval_request_id BIGINT NULL,
    approved_at TIMESTAMP(6) NULL,
    published_at TIMESTAMP(6) NULL,
    disabled_at TIMESTAMP(6) NULL,
    created_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_scenario_version (scenario_id, version_no),
    UNIQUE KEY uk_scenario_checksum (scenario_id, checksum),
    INDEX idx_scenario_version_status (status, validation_status),
    INDEX idx_scenario_version_contract (contract_version_id),
    INDEX idx_scenario_version_approval (approval_request_id),
    CONSTRAINT fk_scenario_version_scenario FOREIGN KEY (scenario_id) REFERENCES mock_scenario (id),
    CONSTRAINT fk_scenario_version_contract
        FOREIGN KEY (contract_version_id) REFERENCES mock_contract_version (id),
    CONSTRAINT fk_scenario_version_approval
        FOREIGN KEY (approval_request_id) REFERENCES mock_approval_request (id),
    CONSTRAINT chk_scenario_version_status CHECK (
        status IN ('DRAFT', 'VALIDATED', 'PENDING_APPROVAL', 'APPROVED', 'PUBLISHED', 'DISABLED')
    ),
    CONSTRAINT chk_scenario_validation_status CHECK (
        validation_status IN ('NOT_VALIDATED', 'VALID', 'INVALID')
    ),
    CONSTRAINT chk_scenario_effective_window CHECK (
        effective_from IS NULL OR effective_to IS NULL OR effective_to > effective_from
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE mock_release (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    release_code VARCHAR(64) NOT NULL,
    environment VARCHAR(32) NOT NULL,
    app_code VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    snapshot_json JSON NOT NULL,
    snapshot_bytes LONGBLOB NOT NULL,
    checksum CHAR(64) NOT NULL,
    schema_version VARCHAR(32) NOT NULL,
    signature VARBINARY(512) NOT NULL,
    signature_key_id VARCHAR(128) NOT NULL,
    signature_algorithm VARCHAR(32) NOT NULL,
    release_note VARCHAR(512) NULL,
    failure_reason VARCHAR(512) NULL,
    created_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    published_by VARCHAR(128) NULL,
    published_at TIMESTAMP(6) NULL,
    UNIQUE KEY uk_release_code (release_code),
    INDEX idx_release_scope_created (environment, app_code, created_at),
    CONSTRAINT chk_release_status CHECK (
        status IN ('PREPARING', 'READY', 'PUBLISHED', 'PARTIAL', 'FAILED')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE mock_release_item (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    release_id VARCHAR(64) NOT NULL,
    item_type VARCHAR(32) NOT NULL,
    object_id BIGINT NOT NULL,
    object_version_id BIGINT NOT NULL,
    UNIQUE KEY uk_release_item (release_id, item_type, object_version_id),
    INDEX idx_release_item_release (release_id),
    CONSTRAINT fk_release_item_release FOREIGN KEY (release_id) REFERENCES mock_release (id),
    CONSTRAINT chk_release_item_type CHECK (
        item_type IN ('CONTRACT', 'FLOW_DEFINITION', 'SCENARIO', 'SECURITY_POLICY')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE mock_active_release (
    environment VARCHAR(32) NOT NULL,
    app_code VARCHAR(128) NOT NULL,
    release_id VARCHAR(64) NULL,
    activation_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    state VARCHAR(32) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (environment, app_code),
    CONSTRAINT fk_active_release FOREIGN KEY (release_id) REFERENCES mock_release (id),
    CONSTRAINT chk_active_release_state CHECK (state IN ('ACTIVATING', 'APPLIED', 'PARTIAL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE mock_release_activation (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    environment VARCHAR(32) NOT NULL,
    app_code VARCHAR(128) NOT NULL,
    from_release_id VARCHAR(64) NULL,
    to_release_id VARCHAR(64) NOT NULL,
    from_activation_version BIGINT UNSIGNED NOT NULL,
    to_activation_version BIGINT UNSIGNED NOT NULL,
    action VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    `operator` VARCHAR(128) NOT NULL,
    deadline_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at TIMESTAMP(6) NULL,
    UNIQUE KEY uk_release_activation_request (request_id),
    UNIQUE KEY uk_release_activation_version (environment, app_code, to_activation_version),
    INDEX idx_release_activation_scope_created (environment, app_code, created_at),
    INDEX idx_release_activation_deadline (status, deadline_at),
    CONSTRAINT fk_activation_from_release FOREIGN KEY (from_release_id) REFERENCES mock_release (id),
    CONSTRAINT fk_activation_to_release FOREIGN KEY (to_release_id) REFERENCES mock_release (id),
    CONSTRAINT chk_release_activation_action CHECK (action IN ('PUBLISH', 'ROLLBACK', 'RECOVER')),
    CONSTRAINT chk_release_activation_status CHECK (
        status IN ('PENDING', 'PROJECTED', 'APPLIED', 'PARTIAL')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE mock_release_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    activation_id VARCHAR(64) NOT NULL,
    aggregate_key VARCHAR(192) NOT NULL,
    activation_version BIGINT UNSIGNED NOT NULL,
    payload_json JSON NOT NULL,
    payload_bytes BLOB NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempt_count INT UNSIGNED NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    lease_owner VARCHAR(128) NULL,
    lease_until TIMESTAMP(6) NULL,
    fencing_token BIGINT UNSIGNED NOT NULL DEFAULT 0,
    last_error_masked VARCHAR(512) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    projected_at TIMESTAMP(6) NULL,
    UNIQUE KEY uk_release_outbox_activation (activation_id),
    INDEX idx_release_outbox_claim (status, next_attempt_at, lease_until),
    CONSTRAINT fk_release_outbox_activation
        FOREIGN KEY (activation_id) REFERENCES mock_release_activation (id),
    CONSTRAINT chk_release_outbox_status CHECK (status IN ('NEW', 'PROJECTED', 'FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE mock_activation_target_node (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    activation_id VARCHAR(64) NOT NULL,
    runtime_node_id VARCHAR(128) NOT NULL,
    required BOOLEAN NOT NULL,
    status VARCHAR(32) NOT NULL,
    captured_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    waived_by VARCHAR(128) NULL,
    waive_reason VARCHAR(512) NULL,
    UNIQUE KEY uk_activation_target (activation_id, runtime_node_id),
    INDEX idx_activation_target_completion (activation_id, required, status),
    CONSTRAINT fk_activation_target_activation
        FOREIGN KEY (activation_id) REFERENCES mock_release_activation (id),
    CONSTRAINT chk_activation_target_status CHECK (
        status IN ('WAITING', 'READY', 'FAILED', 'LEFT', 'WAIVED')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE mock_runtime_activation_ack (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    environment VARCHAR(32) NOT NULL,
    app_code VARCHAR(128) NOT NULL,
    runtime_node_id VARCHAR(128) NOT NULL,
    release_id VARCHAR(64) NOT NULL,
    activation_version BIGINT UNSIGNED NOT NULL,
    status VARCHAR(32) NOT NULL,
    error_masked VARCHAR(512) NULL,
    reported_at TIMESTAMP(6) NOT NULL,
    UNIQUE KEY uk_runtime_activation_ack (
        environment, app_code, runtime_node_id, activation_version
    ),
    INDEX idx_runtime_activation_ack_status (
        environment, app_code, activation_version, status
    ),
    CONSTRAINT fk_runtime_ack_release FOREIGN KEY (release_id) REFERENCES mock_release (id),
    CONSTRAINT chk_runtime_activation_ack_status CHECK (status IN ('READY', 'FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Consolidated pre-release MVP schema: security policies and SDK config (formerly V4).

CREATE TABLE mock_security_policy_version (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    policy_id VARCHAR(64) NOT NULL,
    policy_type VARCHAR(64) NOT NULL,
    scope_key VARCHAR(512) NOT NULL,
    version_no INT NOT NULL,
    config_json_encrypted MEDIUMTEXT NOT NULL,
    checksum CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    signature TEXT NULL,
    signature_key_id VARCHAR(128) NULL,
    signature_algorithm VARCHAR(64) NULL,
    source_audit_ref VARCHAR(256) NULL,
    approval_request_id BIGINT NULL,
    created_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    published_by VARCHAR(128) NULL,
    published_at TIMESTAMP(6) NULL,
    UNIQUE KEY uk_security_policy_version (policy_id, version_no),
    UNIQUE KEY uk_security_policy_scope_checksum (policy_type, scope_key, checksum),
    INDEX idx_security_policy_scope_status (policy_type, scope_key, status),
    INDEX idx_security_policy_approval (approval_request_id),
    CONSTRAINT fk_security_policy_approval
        FOREIGN KEY (approval_request_id) REFERENCES mock_approval_request (id),
    CONSTRAINT chk_security_policy_status
        CHECK (status IN ('DRAFT', 'VALIDATED', 'APPROVED', 'PUBLISHED', 'DEPRECATED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE mock_security_policy_binding (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    policy_type VARCHAR(64) NOT NULL,
    scope_key VARCHAR(512) NOT NULL,
    desired_policy_version_id BIGINT NOT NULL,
    effective_policy_version_id BIGINT NULL,
    effect_mode VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    binding_version BIGINT NOT NULL,
    desired_at TIMESTAMP(6) NOT NULL,
    bound_at TIMESTAMP(6) NULL,
    first_effective_release_id VARCHAR(64) NULL,
    current_effective_release_id VARCHAR(64) NULL,
    effective_activation_version BIGINT NULL,
    sdk_effective_config_version BIGINT NULL,
    effective_at TIMESTAMP(6) NULL,
    updated_by VARCHAR(128) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_security_policy_binding_scope (policy_type, scope_key),
    INDEX idx_security_policy_binding_desired (desired_policy_version_id),
    INDEX idx_security_policy_binding_effective (effective_policy_version_id),
    CONSTRAINT fk_security_policy_binding_desired
        FOREIGN KEY (desired_policy_version_id) REFERENCES mock_security_policy_version (id),
    CONSTRAINT fk_security_policy_binding_effective
        FOREIGN KEY (effective_policy_version_id) REFERENCES mock_security_policy_version (id),
    CONSTRAINT chk_security_policy_effect_mode
        CHECK (effect_mode IN ('LIVE_ADMISSION', 'RELEASE', 'SDK_CONFIG')),
    CONSTRAINT chk_security_policy_binding_status
        CHECK (status IN ('PUBLISHING', 'BOUND', 'INACTIVE')),
    CONSTRAINT chk_security_policy_binding_version CHECK (binding_version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE mock_config_publish_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(128) NOT NULL,
    target_type VARCHAR(64) NOT NULL,
    target_namespace VARCHAR(256) NOT NULL,
    payload_encrypted MEDIUMTEXT NOT NULL,
    checksum CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) NULL,
    last_error_masked VARCHAR(512) NULL,
    lease_owner VARCHAR(128) NULL,
    lease_until TIMESTAMP(6) NULL,
    fencing_token BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    published_at TIMESTAMP(6) NULL,
    UNIQUE KEY uk_config_outbox_aggregate_target (
        aggregate_type, aggregate_id, target_type, target_namespace
    ),
    INDEX idx_config_outbox_due (status, next_attempt_at, lease_until),
    CONSTRAINT chk_config_outbox_aggregate_type
        CHECK (aggregate_type IN ('ADMISSION_BINDING', 'SDK_CONFIG_ACTIVATION')),
    CONSTRAINT chk_config_outbox_status
        CHECK (status IN ('NEW', 'PUBLISHED', 'FAILED')),
    CONSTRAINT chk_config_outbox_attempt_count CHECK (attempt_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE mock_runtime_policy_ack (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    runtime_node_id VARCHAR(128) NOT NULL,
    binding_id VARCHAR(64) NOT NULL,
    environment VARCHAR(32) NOT NULL,
    app_code VARCHAR(128) NOT NULL,
    policy_type VARCHAR(64) NOT NULL,
    policy_version_id BIGINT NOT NULL,
    binding_version BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    error_masked VARCHAR(512) NULL,
    reported_at TIMESTAMP(6) NOT NULL,
    UNIQUE KEY uk_runtime_policy_ack (runtime_node_id, binding_id, binding_version),
    INDEX idx_runtime_policy_ack_binding (binding_id, binding_version, status),
    CONSTRAINT fk_runtime_policy_ack_binding
        FOREIGN KEY (binding_id) REFERENCES mock_security_policy_binding (id),
    CONSTRAINT fk_runtime_policy_ack_version
        FOREIGN KEY (policy_version_id) REFERENCES mock_security_policy_version (id),
    CONSTRAINT chk_runtime_policy_ack_type CHECK (policy_type = 'APP_ACL'),
    CONSTRAINT chk_runtime_policy_ack_status CHECK (status IN ('READY', 'FAILED', 'STALE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE mock_sdk_config_envelope (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    app_code VARCHAR(128) NOT NULL,
    environment VARCHAR(32) NOT NULL,
    config_version BIGINT NOT NULL,
    routing_json JSON NOT NULL,
    security_policy_refs_json JSON NOT NULL,
    security_policy_payloads_encrypted MEDIUMTEXT NOT NULL,
    effective_at TIMESTAMP(6) NOT NULL,
    expire_at TIMESTAMP(6) NULL,
    checksum CHAR(64) NOT NULL,
    signature TEXT NULL,
    signature_key_id VARCHAR(128) NULL,
    signature_algorithm VARCHAR(64) NULL,
    validation_status VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    approval_request_id BIGINT NULL,
    source_audit_ref VARCHAR(256) NULL,
    created_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    published_by VARCHAR(128) NULL,
    published_at TIMESTAMP(6) NULL,
    UNIQUE KEY uk_sdk_config_version (app_code, environment, config_version),
    UNIQUE KEY uk_sdk_config_checksum (app_code, environment, checksum),
    INDEX idx_sdk_config_status (app_code, environment, status),
    INDEX idx_sdk_config_approval (approval_request_id),
    CONSTRAINT fk_sdk_config_approval
        FOREIGN KEY (approval_request_id) REFERENCES mock_approval_request (id),
    CONSTRAINT chk_sdk_config_validation_status
        CHECK (validation_status IN ('NOT_VALIDATED', 'VALID')),
    CONSTRAINT chk_sdk_config_status
        CHECK (status IN (
            'DRAFT', 'VALIDATED', 'PENDING_APPROVAL', 'APPROVED',
            'PUBLISHING', 'PUBLISHED', 'DEPRECATED'
        )),
    CONSTRAINT chk_sdk_config_expiry CHECK (expire_at IS NULL OR expire_at > effective_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE mock_sdk_config_activation (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    app_code VARCHAR(128) NOT NULL,
    environment VARCHAR(32) NOT NULL,
    sdk_config_envelope_id BIGINT NOT NULL,
    from_config_version BIGINT NULL,
    to_config_version BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    `operator` VARCHAR(128) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    completed_at TIMESTAMP(6) NULL,
    UNIQUE KEY uk_sdk_config_activation_request (request_id),
    UNIQUE KEY uk_sdk_config_activation_version (app_code, environment, to_config_version),
    INDEX idx_sdk_config_activation_created (app_code, environment, created_at),
    CONSTRAINT fk_sdk_config_activation_envelope
        FOREIGN KEY (sdk_config_envelope_id) REFERENCES mock_sdk_config_envelope (id),
    CONSTRAINT chk_sdk_config_activation_status
        CHECK (status IN ('PENDING', 'PROJECTED', 'APPLIED', 'PARTIAL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE mock_sdk_config_target_instance (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    activation_id VARCHAR(64) NOT NULL,
    sdk_instance_id VARCHAR(128) NOT NULL,
    required BOOLEAN NOT NULL,
    status VARCHAR(32) NOT NULL,
    captured_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    waived_by VARCHAR(128) NULL,
    waive_reason VARCHAR(512) NULL,
    UNIQUE KEY uk_sdk_config_target (activation_id, sdk_instance_id),
    INDEX idx_sdk_config_target_completion (activation_id, required, status),
    CONSTRAINT fk_sdk_config_target_activation
        FOREIGN KEY (activation_id) REFERENCES mock_sdk_config_activation (id),
    CONSTRAINT chk_sdk_config_target_status
        CHECK (status IN ('WAITING', 'APPLIED', 'REJECTED', 'LEFT', 'WAIVED')),
    CONSTRAINT chk_sdk_config_target_required
        CHECK ((required = TRUE AND status IN ('WAITING', 'APPLIED', 'REJECTED'))
            OR (required = FALSE AND status IN ('LEFT', 'WAIVED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE mock_active_sdk_config (
    app_code VARCHAR(128) NOT NULL,
    environment VARCHAR(32) NOT NULL,
    desired_envelope_id BIGINT NOT NULL,
    desired_config_version BIGINT NOT NULL,
    last_applied_envelope_id BIGINT NULL,
    last_applied_config_version BIGINT NULL,
    activation_id VARCHAR(64) NOT NULL,
    state VARCHAR(32) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (app_code, environment),
    CONSTRAINT fk_active_sdk_desired_envelope
        FOREIGN KEY (desired_envelope_id) REFERENCES mock_sdk_config_envelope (id),
    CONSTRAINT fk_active_sdk_applied_envelope
        FOREIGN KEY (last_applied_envelope_id) REFERENCES mock_sdk_config_envelope (id),
    CONSTRAINT fk_active_sdk_activation
        FOREIGN KEY (activation_id) REFERENCES mock_sdk_config_activation (id),
    CONSTRAINT chk_active_sdk_state CHECK (state IN ('ACTIVATING', 'APPLIED', 'PARTIAL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE mock_sdk_config_event (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    app_code VARCHAR(128) NOT NULL,
    environment VARCHAR(32) NOT NULL,
    sdk_instance_id VARCHAR(128) NOT NULL,
    sdk_config_envelope_id BIGINT NOT NULL,
    sdk_config_activation_id VARCHAR(64) NOT NULL,
    old_config_version BIGINT NULL,
    new_config_version BIGINT NOT NULL,
    security_policy_refs_json JSON NULL,
    status VARCHAR(32) NOT NULL,
    effective_at TIMESTAMP(6) NULL,
    error_masked VARCHAR(512) NULL,
    source_audit_ref VARCHAR(256) NULL,
    received_at TIMESTAMP(6) NOT NULL,
    UNIQUE KEY uk_sdk_config_event_activation_instance (sdk_config_activation_id, sdk_instance_id),
    INDEX idx_sdk_config_event_effective (app_code, environment, effective_at),
    CONSTRAINT fk_sdk_config_event_envelope
        FOREIGN KEY (sdk_config_envelope_id) REFERENCES mock_sdk_config_envelope (id),
    CONSTRAINT fk_sdk_config_event_activation
        FOREIGN KEY (sdk_config_activation_id) REFERENCES mock_sdk_config_activation (id),
    CONSTRAINT chk_sdk_config_event_status CHECK (status IN ('APPLIED', 'REJECTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

UPDATE mock_platform_bootstrap SET schema_version = 'M2' WHERE id = 1;

-- Consolidated pre-release MVP schema: Flow, execution and callback (formerly V5).

CREATE TABLE mock_flow_definition (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    provider_id BIGINT NOT NULL,
    flow_code VARCHAR(64) NOT NULL,
    flow_name VARCHAR(128) NOT NULL,
    current_draft_version INT NULL,
    status VARCHAR(32) NOT NULL,
    created_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_by VARCHAR(128) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_flow_definition_provider_code (provider_id, flow_code),
    INDEX idx_flow_definition_provider_status (provider_id, status),
    CONSTRAINT fk_flow_definition_provider
        FOREIGN KEY (provider_id) REFERENCES mock_provider (id),
    CONSTRAINT chk_flow_definition_status CHECK (status IN ('ENABLED', 'DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE mock_flow_definition_version (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    flow_definition_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    initial_state VARCHAR(64) NOT NULL,
    ttl_seconds BIGINT UNSIGNED NOT NULL,
    participant_apis_json JSON NOT NULL,
    variables_json JSON NOT NULL,
    transitions_json JSON NOT NULL,
    compiled_json JSON NULL,
    checksum CHAR(64) NOT NULL,
    validation_status VARCHAR(32) NOT NULL,
    validation_result_json JSON NULL,
    approval_request_id BIGINT NULL,
    approved_at TIMESTAMP(6) NULL,
    published_at TIMESTAMP(6) NULL,
    deprecated_at TIMESTAMP(6) NULL,
    created_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_flow_definition_version (flow_definition_id, version_no),
    UNIQUE KEY uk_flow_definition_checksum (flow_definition_id, checksum),
    INDEX idx_flow_definition_version_status (status, validation_status),
    INDEX idx_flow_definition_version_approval (approval_request_id),
    CONSTRAINT fk_flow_definition_version_definition
        FOREIGN KEY (flow_definition_id) REFERENCES mock_flow_definition (id),
    CONSTRAINT fk_flow_definition_version_approval
        FOREIGN KEY (approval_request_id) REFERENCES mock_approval_request (id),
    CONSTRAINT chk_flow_definition_version_status CHECK (
        status IN ('DRAFT', 'VALIDATED', 'APPROVED', 'PUBLISHED', 'DEPRECATED')
    ),
    CONSTRAINT chk_flow_definition_validation_status CHECK (
        validation_status IN ('NOT_VALIDATED', 'VALID', 'INVALID')
    ),
    CONSTRAINT chk_flow_definition_ttl CHECK (ttl_seconds > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE mock_scenario_version
    ADD CONSTRAINT fk_scenario_version_flow_definition
        FOREIGN KEY (flow_definition_version_id) REFERENCES mock_flow_definition_version (id);

CREATE TABLE mock_admin_operation (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    operation_type VARCHAR(64) NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    resource_id VARCHAR(128) NOT NULL,
    request_checksum CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    result_json JSON NULL,
    `operator` VARCHAR(128) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at TIMESTAMP(6) NULL,
    UNIQUE KEY uk_admin_operation_request (request_id),
    INDEX idx_admin_operation_resource (
        resource_type, resource_id, operation_type, created_at
    ),
    CONSTRAINT chk_admin_operation_status CHECK (status IN ('IN_TRANSACTION', 'COMPLETED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE mock_flow_instance (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    flow_key VARCHAR(128) NOT NULL,
    environment VARCHAR(32) NOT NULL,
    app_code VARCHAR(128) NOT NULL,
    provider_code VARCHAR(64) NOT NULL,
    flow_code VARCHAR(64) NOT NULL,
    tenant_code VARCHAR(128) NOT NULL DEFAULT '',
    test_account VARCHAR(128) NOT NULL DEFAULT '',
    business_no_hmac CHAR(64) NOT NULL,
    hmac_key_version VARCHAR(32) NOT NULL,
    business_no_masked VARCHAR(256) NULL,
    release_id VARCHAR(64) NOT NULL,
    flow_definition_version_id BIGINT NOT NULL,
    flow_definition_checksum CHAR(64) NOT NULL,
    generation INT UNSIGNED NOT NULL DEFAULT 1,
    status VARCHAR(32) NOT NULL,
    current_state VARCHAR(64) NOT NULL,
    query_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    variables_json JSON NOT NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    pending_transition_id VARCHAR(128) NULL,
    next_transition_at TIMESTAMP(6) NULL,
    expire_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_flow_instance_key (flow_key),
    UNIQUE KEY uk_flow_instance_business (
        environment, app_code, provider_code, flow_code, tenant_code,
        test_account, hmac_key_version, business_no_hmac
    ),
    INDEX idx_flow_instance_state (
        environment, app_code, provider_code, flow_code, current_state
    ),
    INDEX idx_flow_instance_business_hmac (hmac_key_version, business_no_hmac),
    INDEX idx_flow_instance_transition (next_transition_at, current_state),
    INDEX idx_flow_instance_expire (expire_at),
    CONSTRAINT fk_flow_instance_release FOREIGN KEY (release_id) REFERENCES mock_release (id),
    CONSTRAINT fk_flow_instance_definition_version
        FOREIGN KEY (flow_definition_version_id) REFERENCES mock_flow_definition_version (id),
    CONSTRAINT chk_flow_instance_generation CHECK (generation > 0),
    CONSTRAINT chk_flow_instance_status CHECK (status IN ('ACTIVE', 'EXPIRED', 'DELETED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE mock_request_execution (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    app_code VARCHAR(128) NOT NULL,
    mock_request_id VARCHAR(64) NOT NULL,
    execution_generation INT UNSIGNED NOT NULL DEFAULT 1,
    request_fingerprint CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    release_id VARCHAR(64) NULL,
    activation_version BIGINT UNSIGNED NULL,
    scenario_version_id BIGINT NULL,
    flow_instance_id BIGINT NULL,
    flow_generation INT UNSIGNED NULL,
    transition_result_json JSON NULL,
    response_status SMALLINT UNSIGNED NULL,
    response_headers_encrypted MEDIUMBLOB NULL,
    response_body_encrypted MEDIUMBLOB NULL,
    fault_type VARCHAR(64) NULL,
    fault_duration_ms BIGINT UNSIGNED NULL,
    side_effect_policy VARCHAR(32) NULL,
    encryption_key_id VARCHAR(128) NULL,
    expire_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at TIMESTAMP(6) NULL,
    UNIQUE KEY uk_request_execution_request (app_code, mock_request_id),
    INDEX idx_request_execution_status_created (status, created_at),
    INDEX idx_request_execution_expire (expire_at),
    INDEX idx_request_execution_flow (flow_instance_id, flow_generation),
    CONSTRAINT fk_request_execution_release FOREIGN KEY (release_id) REFERENCES mock_release (id),
    CONSTRAINT fk_request_execution_scenario
        FOREIGN KEY (scenario_version_id) REFERENCES mock_scenario_version (id),
    CONSTRAINT fk_request_execution_flow FOREIGN KEY (flow_instance_id) REFERENCES mock_flow_instance (id),
    CONSTRAINT chk_request_execution_generation CHECK (execution_generation > 0),
    CONSTRAINT chk_request_execution_status CHECK (status IN ('IN_TRANSACTION', 'COMPLETED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE mock_flow_event (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_id VARCHAR(64) NOT NULL,
    flow_instance_id BIGINT NOT NULL,
    flow_generation INT UNSIGNED NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    mock_request_id VARCHAR(64) NULL,
    request_execution_generation INT UNSIGNED NULL,
    internal_execution_id VARCHAR(256) NULL,
    source_api_code VARCHAR(64) NULL,
    transition_id VARCHAR(128) NULL,
    from_state VARCHAR(64) NULL,
    to_state VARCHAR(64) NULL,
    event_type VARCHAR(64) NOT NULL,
    query_count BIGINT UNSIGNED NOT NULL,
    `operator` VARCHAR(128) NULL,
    event_at TIMESTAMP(6) NOT NULL,
    UNIQUE KEY uk_flow_event_id (event_id),
    UNIQUE KEY uk_flow_event_transition (
        flow_instance_id, flow_generation, transition_id
    ),
    UNIQUE KEY uk_flow_event_sdk_execution (
        flow_instance_id, mock_request_id, request_execution_generation, event_type
    ),
    UNIQUE KEY uk_flow_event_internal_execution (internal_execution_id),
    INDEX idx_flow_event_instance_time (flow_instance_id, event_at),
    CONSTRAINT fk_flow_event_instance FOREIGN KEY (flow_instance_id) REFERENCES mock_flow_instance (id),
    CONSTRAINT chk_flow_event_generation CHECK (flow_generation > 0),
    CONSTRAINT chk_flow_event_source CHECK (source_type IN ('SDK', 'TIMER', 'MANUAL')),
    CONSTRAINT chk_flow_event_source_identity CHECK (
        (source_type = 'SDK'
            AND mock_request_id IS NOT NULL
            AND request_execution_generation IS NOT NULL
            AND internal_execution_id IS NULL)
        OR
        (source_type IN ('TIMER', 'MANUAL')
            AND mock_request_id IS NULL
            AND request_execution_generation IS NULL
            AND internal_execution_id IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE mock_callback_task (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    task_id VARCHAR(64) NOT NULL,
    delivery_id VARCHAR(64) NOT NULL,
    flow_event_id BIGINT NOT NULL,
    flow_instance_id BIGINT NOT NULL,
    flow_generation INT UNSIGNED NOT NULL,
    release_id VARCHAR(64) NOT NULL,
    snapshot_checksum CHAR(64) NOT NULL,
    callback_definition_id VARCHAR(128) NOT NULL,
    delivery_index INT UNSIGNED NOT NULL,
    provider_code VARCHAR(64) NOT NULL,
    api_code VARCHAR(64) NOT NULL,
    callback_url_encrypted BLOB NOT NULL,
    http_method VARCHAR(16) NOT NULL,
    headers_json_encrypted MEDIUMBLOB NOT NULL,
    payload_encrypted MEDIUMBLOB NOT NULL,
    rendered_payload_hash CHAR(64) NOT NULL,
    encryption_key_id VARCHAR(128) NOT NULL,
    callback_signature_policy_version_id BIGINT NOT NULL,
    callback_allowlist_policy_version_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    next_execute_at TIMESTAMP(6) NOT NULL,
    send_attempt_count INT UNSIGNED NOT NULL DEFAULT 0,
    max_retry INT UNSIGNED NOT NULL,
    retry_intervals_json JSON NOT NULL,
    preparation_retry_count INT UNSIGNED NOT NULL DEFAULT 0,
    max_preparation_retry INT UNSIGNED NOT NULL,
    manual_send_grant_count INT UNSIGNED NOT NULL DEFAULT 0,
    manual_preparation_grant_count INT UNSIGNED NOT NULL DEFAULT 0,
    lease_owner VARCHAR(128) NULL,
    lease_until TIMESTAMP(6) NULL,
    fencing_token BIGINT UNSIGNED NOT NULL DEFAULT 0,
    last_http_status SMALLINT UNSIGNED NULL,
    last_error_masked VARCHAR(512) NULL,
    expire_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_callback_task_id (task_id),
    UNIQUE KEY uk_callback_delivery_id (delivery_id),
    UNIQUE KEY uk_callback_event_delivery (
        flow_event_id, flow_generation, callback_definition_id, delivery_index
    ),
    INDEX idx_callback_task_claim (status, next_execute_at, id),
    INDEX idx_callback_task_lease (lease_until),
    INDEX idx_callback_task_expire (expire_at),
    INDEX idx_callback_task_flow_generation (flow_instance_id, flow_generation, status),
    CONSTRAINT fk_callback_task_event FOREIGN KEY (flow_event_id) REFERENCES mock_flow_event (id),
    CONSTRAINT fk_callback_task_flow FOREIGN KEY (flow_instance_id) REFERENCES mock_flow_instance (id),
    CONSTRAINT fk_callback_task_release FOREIGN KEY (release_id) REFERENCES mock_release (id),
    CONSTRAINT fk_callback_task_signature_policy
        FOREIGN KEY (callback_signature_policy_version_id) REFERENCES mock_security_policy_version (id),
    CONSTRAINT fk_callback_task_allowlist_policy
        FOREIGN KEY (callback_allowlist_policy_version_id) REFERENCES mock_security_policy_version (id),
    CONSTRAINT chk_callback_task_generation CHECK (flow_generation > 0),
    CONSTRAINT chk_callback_task_method CHECK (http_method IN ('POST', 'PUT', 'PATCH')),
    CONSTRAINT chk_callback_task_status CHECK (
        status IN (
            'NEW', 'RETRYING', 'RUNNING', 'SUCCESS', 'FAILED',
            'FAILED_PREPARATION', 'FAILED_UNCONFIRMED', 'CANCELLED'
        )
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE mock_callback_attempt (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    task_id VARCHAR(64) NOT NULL,
    delivery_id VARCHAR(64) NOT NULL,
    attempt_no INT UNSIGNED NOT NULL,
    send_attempt_no INT UNSIGNED NULL,
    fencing_token BIGINT UNSIGNED NOT NULL,
    status VARCHAR(32) NOT NULL,
    started_at TIMESTAMP(6) NOT NULL,
    completed_at TIMESTAMP(6) NULL,
    http_status SMALLINT UNSIGNED NULL,
    result VARCHAR(64) NULL,
    delivery_certainty VARCHAR(32) NULL,
    error_masked VARCHAR(512) NULL,
    duration_ms BIGINT UNSIGNED NULL,
    UNIQUE KEY uk_callback_attempt (task_id, attempt_no),
    UNIQUE KEY uk_callback_send_attempt (task_id, send_attempt_no),
    INDEX idx_callback_attempt_delivery (delivery_id, attempt_no),
    CONSTRAINT fk_callback_attempt_task FOREIGN KEY (task_id) REFERENCES mock_callback_task (task_id),
    CONSTRAINT chk_callback_attempt_no CHECK (attempt_no > 0),
    CONSTRAINT chk_callback_attempt_status CHECK (
        status IN (
            'PREPARING', 'PREPARATION_FAILED', 'ABANDONED_PREPARATION',
            'STARTED', 'SUCCESS', 'FAILED', 'ABANDONED', 'CANCELLED'
        )
    ),
    CONSTRAINT chk_callback_delivery_certainty CHECK (
        delivery_certainty IS NULL
        OR delivery_certainty IN ('NEVER_SENT', 'CONFIRMED_RESPONSE', 'UNKNOWN')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Consolidated pre-release MVP schema: Request Log partitions (formerly V6).

-- MySQL rejects fractional TIMESTAMP expressions as partition keys. A stored date
-- column preserves TIMESTAMP(6) authority while providing a supported RANGE COLUMNS key.
ALTER TABLE mock_request_log
    ADD COLUMN created_day DATE GENERATED ALWAYS AS (DATE(created_at)) STORED AFTER created_at,
    DROP PRIMARY KEY,
    ADD PRIMARY KEY (created_at, id, created_day);

-- Request Log is intentionally partitioned by day. The rolling maintenance
-- component adds future partitions before p_future and drops expired daily partitions.
ALTER TABLE mock_request_log
PARTITION BY RANGE COLUMNS (created_day) (
    PARTITION p_legacy VALUES LESS THAN ('2026-08-24'),
    PARTITION p20260824 VALUES LESS THAN ('2026-08-25'),
    PARTITION p20260825 VALUES LESS THAN ('2026-08-26'),
    PARTITION p20260826 VALUES LESS THAN ('2026-08-27'),
    PARTITION p20260827 VALUES LESS THAN ('2026-08-28'),
    PARTITION p20260828 VALUES LESS THAN ('2026-08-29'),
    PARTITION p20260829 VALUES LESS THAN ('2026-08-30'),
    PARTITION p20260830 VALUES LESS THAN ('2026-08-31'),
    PARTITION p20260831 VALUES LESS THAN ('2026-09-01'),
    PARTITION p20260901 VALUES LESS THAN ('2026-09-02'),
    PARTITION p20260902 VALUES LESS THAN ('2026-09-03'),
    PARTITION p20260903 VALUES LESS THAN ('2026-09-04'),
    PARTITION p20260904 VALUES LESS THAN ('2026-09-05'),
    PARTITION p20260905 VALUES LESS THAN ('2026-09-06'),
    PARTITION p20260906 VALUES LESS THAN ('2026-09-07'),
    PARTITION p20260907 VALUES LESS THAN ('2026-09-08'),
    PARTITION p20260908 VALUES LESS THAN ('2026-09-09'),
    PARTITION p20260909 VALUES LESS THAN ('2026-09-10'),
    PARTITION p20260910 VALUES LESS THAN ('2026-09-11'),
    PARTITION p20260911 VALUES LESS THAN ('2026-09-12'),
    PARTITION p20260912 VALUES LESS THAN ('2026-09-13'),
    PARTITION p20260913 VALUES LESS THAN ('2026-09-14'),
    PARTITION p20260914 VALUES LESS THAN ('2026-09-15'),
    PARTITION p20260915 VALUES LESS THAN ('2026-09-16'),
    PARTITION p20260916 VALUES LESS THAN ('2026-09-17'),
    PARTITION p20260917 VALUES LESS THAN ('2026-09-18'),
    PARTITION p20260918 VALUES LESS THAN ('2026-09-19'),
    PARTITION p20260919 VALUES LESS THAN ('2026-09-20'),
    PARTITION p20260920 VALUES LESS THAN ('2026-09-21'),
    PARTITION p20260921 VALUES LESS THAN ('2026-09-22'),
    PARTITION p20260922 VALUES LESS THAN ('2026-09-23'),
    PARTITION p20260923 VALUES LESS THAN ('2026-09-24'),
    PARTITION p20260924 VALUES LESS THAN ('2026-09-25'),
    PARTITION p20260925 VALUES LESS THAN ('2026-09-26'),
    PARTITION p20260926 VALUES LESS THAN ('2026-09-27'),
    PARTITION p20260927 VALUES LESS THAN ('2026-09-28'),
    PARTITION p20260928 VALUES LESS THAN ('2026-09-29'),
    PARTITION p20260929 VALUES LESS THAN ('2026-09-30'),
    PARTITION p20260930 VALUES LESS THAN ('2026-10-01'),
    PARTITION p20261001 VALUES LESS THAN ('2026-10-02'),
    PARTITION p_future VALUES LESS THAN (MAXVALUE)
);

-- Consolidated pre-release MVP schema: minute request metrics (formerly V7).

CREATE TABLE mock_request_metric_minute (
    bucket_start TIMESTAMP NOT NULL,
    request_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    matched_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    no_match_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    latency_0_5_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    latency_6_10_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    latency_11_25_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    latency_26_50_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    latency_51_100_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    latency_101_250_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    latency_251_500_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    latency_501_1000_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    latency_1001_3000_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    latency_over_3000_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    max_duration_ms BIGINT UNSIGNED NOT NULL DEFAULT 0,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (bucket_start)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO mock_request_metric_minute (
    bucket_start, request_count, matched_count, no_match_count,
    latency_0_5_count, latency_6_10_count, latency_11_25_count,
    latency_26_50_count, latency_51_100_count, latency_101_250_count,
    latency_251_500_count, latency_501_1000_count, latency_1001_3000_count,
    latency_over_3000_count, max_duration_ms
)
SELECT
    TIMESTAMP(DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:00')),
    COUNT(*),
    SUM(match_result = 'MATCHED'),
    SUM(error_code = 'MOCK_NO_MATCH'),
    SUM(duration_ms <= 5),
    SUM(duration_ms BETWEEN 6 AND 10),
    SUM(duration_ms BETWEEN 11 AND 25),
    SUM(duration_ms BETWEEN 26 AND 50),
    SUM(duration_ms BETWEEN 51 AND 100),
    SUM(duration_ms BETWEEN 101 AND 250),
    SUM(duration_ms BETWEEN 251 AND 500),
    SUM(duration_ms BETWEEN 501 AND 1000),
    SUM(duration_ms BETWEEN 1001 AND 3000),
    SUM(duration_ms > 3000),
    MAX(duration_ms)
FROM mock_request_log
GROUP BY TIMESTAMP(DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:00'));

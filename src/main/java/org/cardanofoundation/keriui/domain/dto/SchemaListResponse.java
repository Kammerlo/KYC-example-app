package org.cardanofoundation.keriui.domain.dto;

import java.util.List;

/**
 * Response returned by GET /api/keri/schemas.
 */
public record SchemaListResponse(List<SchemaItem> schemas) {}

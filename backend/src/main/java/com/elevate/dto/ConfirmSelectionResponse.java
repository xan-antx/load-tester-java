package com.elevate.dto;

import java.util.List;

/**
 * Body of {@code /api/confirm-selection}.
 *
 * @param confirmedCount number of cases
 * @param confirmedCases the cases to send
 */
public record ConfirmSelectionResponse(int confirmedCount, List<ConfirmedCase> confirmedCases) {
}

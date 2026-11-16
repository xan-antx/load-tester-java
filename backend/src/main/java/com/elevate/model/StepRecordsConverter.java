package com.elevate.model;

import java.util.List;

import com.elevate.analysis.capacity.StepRecord;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Stores a capacity run's step records as one JSON text column (they are always read together). */
@Converter
public class StepRecordsConverter implements AttributeConverter<List<StepRecord>, String> {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Override
    public String convertToDatabaseColumn(List<StepRecord> steps) {
        return steps == null || steps.isEmpty() ? null : JSON.writeValueAsString(steps);
    }

    @Override
    public List<StepRecord> convertToEntityAttribute(String column) {
        return column == null || column.isBlank() ? List.of() : JSON.readValue(column, new TypeReference<>() { });
    }
}

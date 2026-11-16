package com.elevate.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/**
 * One case a run sends: an edge case of an API run (POST with a JSON body and an
 * expectation) or a page of a website run (GET, no body, no expectation).
 */
@Entity
@Table(name = "run_case")
public class RunCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 512)
    private String label;

    @Column(length = 32)
    private String category;

    @Column(length = 32)
    private String classifiedAs;

    @Column(length = 1000)
    private String description;

    @Column(nullable = false, length = 8)
    private String method;

    @Lob
    private String payloadJson;

    @Enumerated(EnumType.STRING)
    @Column(length = 8)
    private Expectation expect;

    /** For JPA. */
    protected RunCase() {
    }

    private RunCase(String label, String category, String classifiedAs, String description, String method,
                    String payloadJson, Expectation expect) {
        this.label = label;
        this.category = category;
        this.classifiedAs = classifiedAs;
        this.description = description;
        this.method = method;
        this.payloadJson = payloadJson;
        this.expect = expect;
    }

    /**
     * An edge case of an API run.
     *
     * @param label        unique label
     * @param category     generator category or {@code custom}
     * @param classifiedAs for custom cases, the category the diff matched
     * @param description  what was changed
     * @param payloadJson  the JSON body
     * @param expect       expected answer, or {@code null} (older clients)
     * @return the case
     */
    public static RunCase edgeCase(String label, String category, String classifiedAs, String description,
                                   String payloadJson, Expectation expect) {
        return new RunCase(label, category, classifiedAs, description, "POST", payloadJson, expect);
    }

    /** A page of a website run. */
    public static RunCase page(String path) {
        return new RunCase(path, "page", null, null, "GET", null, null);
    }

    /** A copy for another run (each child of a split run owns its cases). */
    public RunCase copy() {
        return new RunCase(label, category, classifiedAs, description, method, payloadJson, expect);
    }

    public String getLabel() {
        return label;
    }

    public String getCategory() {
        return category;
    }

    public String getClassifiedAs() {
        return classifiedAs;
    }

    public String getDescription() {
        return description;
    }

    public String getMethod() {
        return method;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public Expectation getExpect() {
        return expect;
    }
}

package com.mobility.core.shared.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Schema;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.stereotype.Component;

/** A nullable object reference must be a union in OpenAPI 3.1, not a null-only $ref sibling. */
@Component
class PassengerLocationOpenApi implements GlobalOpenApiCustomizer {
    @Override
    public void customise(OpenAPI api) {
        if (api.getComponents() == null || api.getComponents().getSchemas() == null) return;
        var quote = api.getComponents().getSchemas().get("QuoteView");
        if (quote == null || quote.getProperties() == null || !quote.getProperties().containsKey("route")) return;
        var absent = new Schema<>();
        absent.setTypes(java.util.Set.of("null"));
        quote.addProperty("route", new ComposedSchema()
                .addAnyOfItem(new Schema<>().$ref("#/components/schemas/Geometry"))
                .addAnyOfItem(absent)
                .description("Immutable priced route geometry; null for straight-line or older quotes"));
    }
}

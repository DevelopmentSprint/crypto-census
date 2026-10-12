package com.developmentsprint.cryptocensus.delivery.profile;

/** A request plus current evidence; a null request marks a terminal session. */
public record ProtocolStep(AdapterRequest request, AdapterResult observation) {}

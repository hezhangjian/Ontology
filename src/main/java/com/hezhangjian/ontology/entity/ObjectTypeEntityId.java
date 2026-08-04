package com.hezhangjian.ontology.entity;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@AllArgsConstructor
@EqualsAndHashCode
@NoArgsConstructor
public class ObjectTypeEntityId implements Serializable {
    private String ontologyId;

    private String id;
}

package org.example.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Entity
@Table(name = "skill")
@Getter
@Setter
public class Skill {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, unique = true, length = 64)
    private String name;

    @Column(nullable = false, length = 32)
    private String category;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Skill parent;

    @OneToMany(mappedBy = "parent", fetch = FetchType.LAZY)
    private List<Skill> children;

    @Column(columnDefinition = "JSON")
    private String keywords;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String description;
}

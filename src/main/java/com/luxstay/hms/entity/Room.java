package com.luxstay.hms.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import com.luxstay.hms.enums.RoomStatus;
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "room")
public class Room extends BaseEntity {

    @Column(nullable = false, unique = true, length = 10)
    private String number;

    @Column(nullable = false)
    private Integer floor;

    @Column(length = 30)
    private String viewType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RoomStatus status;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_type_id")
    private RoomType roomType;
}
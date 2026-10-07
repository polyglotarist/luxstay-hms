package com.luxstay.hms.repository;

import com.luxstay.hms.entity.Room;
import com.luxstay.hms.enums.RoomStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoomRepository extends JpaRepository<Room, Long> {

    boolean existsByNumber(String number);

    @EntityGraph(attributePaths = "roomType")
    Page<Room> findByStatus(RoomStatus status, Pageable pageable);

    @Override
    @EntityGraph(attributePaths = "roomType")
    Page<Room> findAll(Pageable pageable);
}
package com.luxstay.hms.integration;

import com.luxstay.hms.TestcontainersConfiguration;
import com.luxstay.hms.config.JpaAuditingConfig;
import com.luxstay.hms.entity.Room;
import com.luxstay.hms.enums.RoomStatus;
import com.luxstay.hms.repository.RoomRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, JpaAuditingConfig.class})
class RoomRepositoryIT {

    @Autowired RoomRepository roomRepository;

    @Test
    void flywaySeed_creates60Rooms() {
        assertThat(roomRepository.count()).isEqualTo(60);
    }

    @Test
    void findByStatus_returnsOnlyMatchingRooms() {
        Page<Room> page = roomRepository.findByStatus(RoomStatus.VACANT_CLEAN, PageRequest.of(0, 100));

        assertThat(page.getContent())
                .isNotEmpty()
                .allMatch(room -> room.getStatus() == RoomStatus.VACANT_CLEAN);
    }
}
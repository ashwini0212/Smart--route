package com.smartroute.events;

import org.springframework.data.jpa.repository.JpaRepository;

interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, ProcessedEvent.Key> {
}

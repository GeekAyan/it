package com.aiimsk.it.repository;


import com.aiimsk.it.model.Event;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventRepository extends JpaRepository<Event, Long> {
}
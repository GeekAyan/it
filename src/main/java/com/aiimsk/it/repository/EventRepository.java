package com.aiimsk.it.repository;

import com.aiimsk.it.model.Event;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface EventRepository extends JpaRepository<Event, Long> {

    List<Event> findByCreatedByEmailOrderByEventDateDesc(String email);

    @Query("select e from Event e " +
            "where (:month is null or extract(month from e.eventDate) = :month) " +
            "and (:year is null or extract(year from e.eventDate) = :year) " +
            "order by e.eventDate desc")
    List<Event> searchByMonthAndYear(@Param("month") Integer month,
            @Param("year") Integer year);

    @Query("select count(e) from Event e where extract(year from e.eventDate) = :year and extract(month from e.eventDate) = :month")
    long countByMonthAndYear(@Param("month") int month, @Param("year") int year);

    @Query("select count(e) from Event e where extract(year from e.eventDate) = :year")
    long countByYear(@Param("year") int year);
}
package com.bitly.clone.repository;


import com.bitly.clone.entity.ClickAnalytics;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClickAnalyticsRepository extends JpaRepository<ClickAnalytics, Long> {
}
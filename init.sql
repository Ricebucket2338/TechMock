CREATE DATABASE `interview_assistant`

USE `interview_assistant`

CREATE TABLE `audio_transcription_task` (
  `id` varchar(36) NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `dashscope_task_id` varchar(64) DEFAULT NULL,
  `duration_sec` int DEFAULT NULL,
  `error_message` varchar(1024) DEFAULT NULL,
  `file_name` varchar(255) DEFAULT NULL,
  `file_path` varchar(512) DEFAULT NULL,
  `file_size` bigint DEFAULT NULL,
  `progress` int NOT NULL,
  `review_report` longtext,
  `speaker_segments` longtext,
  `status` varchar(20) NOT NULL,
  `transcript_text` longtext,
  `updated_at` datetime(6) DEFAULT NULL,
  `upload_id` varchar(36) DEFAULT NULL,
  `session_id` varchar(36) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FK73k5rh1hx511pr51j2d36kika` (`session_id`),
  CONSTRAINT `FK73k5rh1hx511pr51j2d36kika` FOREIGN KEY (`session_id`) REFERENCES `interview_session` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


CREATE TABLE `candidate_skill` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `assessed_level` int NOT NULL,
  `confidence` float DEFAULT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `evidence` text,
  `source` varchar(20) NOT NULL,
  `session_id` varchar(36) DEFAULT NULL,
  `skill_id` varchar(36) NOT NULL,
  `user_id` varchar(36) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_skill_session` (`user_id`,`skill_id`,`session_id`),
  KEY `FKdldnymrj7fi8s59mkg74vvgwy` (`session_id`),
  KEY `FKb7cxhiqhcah7c20a2cdlvr1f8` (`skill_id`),
  CONSTRAINT `FKb7cxhiqhcah7c20a2cdlvr1f8` FOREIGN KEY (`skill_id`) REFERENCES `skill` (`id`),
  CONSTRAINT `FKdldnymrj7fi8s59mkg74vvgwy` FOREIGN KEY (`session_id`) REFERENCES `interview_session` (`id`),
  CONSTRAINT `FKovhpt8ewv05nqgcwm426lrxu7` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


CREATE TABLE `interview_session` (
  `id` varchar(36) NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `mode` varchar(20) NOT NULL,
  `overall_score` varchar(10) DEFAULT NULL,
  `resume_id` varchar(36) DEFAULT NULL,
  `status` varchar(20) NOT NULL,
  `summary` text,
  `target_position` varchar(128) DEFAULT NULL,
  `total_duration_sec` int DEFAULT NULL,
  `turn_count` int NOT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `user_id` varchar(36) NOT NULL,
  `interview_type` varchar(20) DEFAULT NULL,
  `covered_topics` varchar(512) DEFAULT NULL,
  `aggressive_count` int NOT NULL,
  `behavior_warnings` text,
  `current_direction` varchar(64) DEFAULT NULL,
  `current_direction_idx` int NOT NULL,
  `direction_order` text,
  `report_data` text,
  `start_time` datetime(6) DEFAULT NULL,
  `topic_pool` text,
  `topic_question_map` text,
  `uncooperative_count` int NOT NULL,
  `phase` varchar(20) NOT NULL,
  `global_topic_queue` text,
  `weak_areas` varchar(256) DEFAULT NULL,
  `current_phase_type` varchar(20) DEFAULT NULL,
  `direction_queue` text,
  `scenario_queue` text,
  `last_asked_direction` varchar(64) DEFAULT NULL,
  `last_asked_point` varchar(128) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FKa7kkt5d9it9u8tqye7cih8wh2` (`user_id`),
  CONSTRAINT `FKa7kkt5d9it9u8tqye7cih8wh2` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `message` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `content` text NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `role` varchar(20) NOT NULL,
  `session_id` varchar(36) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FKnxi8eu5ayljgi4kjr1m991bjb` (`session_id`),
  CONSTRAINT `FKnxi8eu5ayljgi4kjr1m991bjb` FOREIGN KEY (`session_id`) REFERENCES `interview_session` (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=596 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `resume` (
  `id` varchar(36) NOT NULL,
  `file_path` varchar(512) DEFAULT NULL,
  `original_filename` varchar(256) DEFAULT NULL,
  `parsed_data` json NOT NULL,
  `summary` text,
  `uploaded_at` datetime(6) DEFAULT NULL,
  `user_id` varchar(36) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FKiqntisdlc7ta7sjr6d8rj5ae2` (`user_id`),
  CONSTRAINT `FKiqntisdlc7ta7sjr6d8rj5ae2` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `skill` (
  `id` varchar(36) NOT NULL,
  `category` varchar(32) NOT NULL,
  `description` text,
  `keywords` json DEFAULT NULL,
  `name` varchar(64) NOT NULL,
  `parent_id` varchar(36) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_5ljf2l2h4odhtxrsuohlro4ir` (`name`),
  KEY `FK60cjhtk61jl6c1038lsl5yl2s` (`parent_id`),
  CONSTRAINT `FK60cjhtk61jl6c1038lsl5yl2s` FOREIGN KEY (`parent_id`) REFERENCES `skill` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `user` (
  `id` varchar(36) NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `nickname` varchar(64) NOT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


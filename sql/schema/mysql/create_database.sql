-- 临床研究数据处理 Agent —— 建库脚本
-- 执行：mysql -uroot -p < create_database.sql

CREATE DATABASE IF NOT EXISTS `data_agent`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_general_ci;

USE `data_agent`;

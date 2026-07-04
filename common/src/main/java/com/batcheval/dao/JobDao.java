package com.batcheval.dao;

import com.batcheval.config.AppConfig;
import com.batcheval.model.JobStatus;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** DAO — SQLite persistence for batch files and jobs. */
public class JobDao implements AutoCloseable {

    private final Connection connection;

    public JobDao(AppConfig config) throws SQLException {
        this.connection = DriverManager.getConnection(config.databaseUrl());
        initSchema();
    }

    JobDao(Connection connection) throws SQLException {
        this.connection = connection;
        initSchema();
    }

    private void initSchema() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS batch_files (
                        file_id TEXT PRIMARY KEY,
                        file_name TEXT NOT NULL,
                        s3_key TEXT NOT NULL,
                        uploaded INTEGER NOT NULL DEFAULT 0,
                        byte_size INTEGER,
                        created_at TEXT NOT NULL,
                        expires_at TEXT NOT NULL
                    )
                    """);
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS batch_jobs (
                        job_id TEXT PRIMARY KEY,
                        file_id TEXT NOT NULL,
                        status TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        started_at TEXT,
                        completed_at TEXT,
                        failed_at TEXT,
                        total INTEGER NOT NULL DEFAULT 0,
                        completed INTEGER NOT NULL DEFAULT 0,
                        failed INTEGER NOT NULL DEFAULT 0,
                        result_s3_key TEXT,
                        download_consumed INTEGER NOT NULL DEFAULT 0,
                        error_message TEXT,
                        high_priority INTEGER NOT NULL DEFAULT 0,
                        expires_at TEXT NOT NULL
                    )
                    """);
            addColumnIfMissing("batch_jobs", "high_priority", "INTEGER NOT NULL DEFAULT 0");
        }
    }

    private void addColumnIfMissing(String table, String column, String definition) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        } catch (SQLException ex) {
            if (!ex.getMessage().contains("duplicate column name")) {
                throw ex;
            }
        }
    }

    public BatchJobRecord createSubmission(
            UUID fileId,
            UUID jobId,
            String fileName,
            String s3Key,
            long byteSize,
            boolean highPriority,
            Instant expiresAt
    ) throws SQLException {
        Instant now = Instant.now();
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            try (PreparedStatement filePs = connection.prepareStatement("""
                    INSERT INTO batch_files (file_id, file_name, s3_key, uploaded, byte_size, created_at, expires_at)
                    VALUES (?, ?, ?, 1, ?, ?, ?)
                    """)) {
                filePs.setString(1, fileId.toString());
                filePs.setString(2, fileName);
                filePs.setString(3, s3Key);
                filePs.setLong(4, byteSize);
                filePs.setString(5, now.toString());
                filePs.setString(6, expiresAt.toString());
                filePs.executeUpdate();
            }
            try (PreparedStatement jobPs = connection.prepareStatement("""
                    INSERT INTO batch_jobs
                    (job_id, file_id, status, created_at, total, completed, failed, download_consumed,
                     high_priority, expires_at)
                    VALUES (?, ?, ?, ?, 0, 0, 0, 0, ?, ?)
                    """)) {
                jobPs.setString(1, jobId.toString());
                jobPs.setString(2, fileId.toString());
                jobPs.setString(3, JobStatus.QUEUED.wire());
                jobPs.setString(4, now.toString());
                jobPs.setInt(5, highPriority ? 1 : 0);
                jobPs.setString(6, expiresAt.toString());
                jobPs.executeUpdate();
            }
            connection.commit();
            return new BatchJobRecord(
                    jobId, fileId, JobStatus.QUEUED, now, null, null, null,
                    0, 0, 0, null, false, null, highPriority, expiresAt
            );
        } catch (SQLException ex) {
            connection.rollback();
            throw ex;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    public BatchFileRecord createFileRecord(String fileName, long byteSize, Instant expiresAt) throws SQLException {
        UUID fileId = UUID.randomUUID();
        String s3Key = "inputs/standard/" + fileId + ".jsonl";
        Instant now = Instant.now();
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO batch_files (file_id, file_name, s3_key, uploaded, byte_size, created_at, expires_at)
                VALUES (?, ?, ?, 1, ?, ?, ?)
                """)) {
            ps.setString(1, fileId.toString());
            ps.setString(2, fileName);
            ps.setString(3, s3Key);
            ps.setLong(4, byteSize);
            ps.setString(5, now.toString());
            ps.setString(6, expiresAt.toString());
            ps.executeUpdate();
        }
        return new BatchFileRecord(fileId, fileName, s3Key, true, byteSize, now, expiresAt);
    }

    public Optional<BatchFileRecord> getFile(UUID fileId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM batch_files WHERE file_id = ?")) {
            ps.setString(1, fileId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapFile(rs));
            }
        }
    }

    public BatchJobRecord createJob(UUID fileId, Instant expiresAt) throws SQLException {
        UUID jobId = UUID.randomUUID();
        Instant now = Instant.now();
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO batch_jobs
                (job_id, file_id, status, created_at, total, completed, failed, download_consumed,
                 high_priority, expires_at)
                VALUES (?, ?, ?, ?, 0, 0, 0, 0, 0, ?)
                """)) {
            ps.setString(1, jobId.toString());
            ps.setString(2, fileId.toString());
            ps.setString(3, JobStatus.QUEUED.wire());
            ps.setString(4, now.toString());
            ps.setString(5, expiresAt.toString());
            ps.executeUpdate();
        }
        return new BatchJobRecord(
                jobId, fileId, JobStatus.QUEUED, now, null, null, null,
                0, 0, 0, null, false, null, false, expiresAt
        );
    }

    public Optional<BatchJobRecord> getJobByFileId(UUID fileId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM batch_jobs WHERE file_id = ?")) {
            ps.setString(1, fileId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapJob(rs));
            }
        }
    }

    public void deleteSubmission(UUID fileId, UUID jobId) throws SQLException {
        try (PreparedStatement jobPs = connection.prepareStatement("DELETE FROM batch_jobs WHERE job_id = ?")) {
            jobPs.setString(1, jobId.toString());
            jobPs.executeUpdate();
        }
        try (PreparedStatement filePs = connection.prepareStatement("DELETE FROM batch_files WHERE file_id = ?")) {
            filePs.setString(1, fileId.toString());
            filePs.executeUpdate();
        }
    }

    public Optional<BatchJobRecord> getJob(UUID jobId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM batch_jobs WHERE job_id = ?")) {
            ps.setString(1, jobId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapJob(rs));
            }
        }
    }

    public void updateJob(BatchJobRecord job) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                UPDATE batch_jobs SET
                    status = ?, started_at = ?, completed_at = ?, failed_at = ?,
                    total = ?, completed = ?, failed = ?,
                    result_s3_key = ?, download_consumed = ?, error_message = ?
                WHERE job_id = ?
                """)) {
            ps.setString(1, job.status().wire());
            ps.setString(2, instantToString(job.startedAt()));
            ps.setString(3, instantToString(job.completedAt()));
            ps.setString(4, instantToString(job.failedAt()));
            ps.setInt(5, job.total());
            ps.setInt(6, job.completed());
            ps.setInt(7, job.failed());
            ps.setString(8, job.resultS3Key());
            ps.setInt(9, job.downloadConsumed() ? 1 : 0);
            ps.setString(10, job.errorMessage());
            ps.setString(11, job.jobId().toString());
            ps.executeUpdate();
        }
    }

    public boolean markDownloadConsumed(UUID jobId) throws SQLException {
        Optional<BatchJobRecord> job = getJob(jobId);
        if (job.isEmpty() || job.get().downloadConsumed()) {
            return false;
        }
        try (PreparedStatement ps = connection.prepareStatement("""
                UPDATE batch_jobs SET download_consumed = 1 WHERE job_id = ?
                """)) {
            ps.setString(1, jobId.toString());
            ps.executeUpdate();
        }
        return true;
    }

    private BatchFileRecord mapFile(ResultSet rs) throws SQLException {
        return new BatchFileRecord(
                UUID.fromString(rs.getString("file_id")),
                rs.getString("file_name"),
                rs.getString("s3_key"),
                rs.getInt("uploaded") == 1,
                rs.getObject("byte_size") == null ? null : rs.getLong("byte_size"),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("expires_at"))
        );
    }

    private BatchJobRecord mapJob(ResultSet rs) throws SQLException {
        return new BatchJobRecord(
                UUID.fromString(rs.getString("job_id")),
                UUID.fromString(rs.getString("file_id")),
                JobStatus.fromWire(rs.getString("status")),
                Instant.parse(rs.getString("created_at")),
                parseInstant(rs.getString("started_at")),
                parseInstant(rs.getString("completed_at")),
                parseInstant(rs.getString("failed_at")),
                rs.getInt("total"),
                rs.getInt("completed"),
                rs.getInt("failed"),
                rs.getString("result_s3_key"),
                rs.getInt("download_consumed") == 1,
                rs.getString("error_message"),
                rs.getInt("high_priority") == 1,
                Instant.parse(rs.getString("expires_at"))
        );
    }

    private static Instant parseInstant(String value) {
        return value == null ? null : Instant.parse(value);
    }

    private static String instantToString(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }

    public record BatchFileRecord(
            UUID fileId,
            String fileName,
            String s3Key,
            boolean uploaded,
            Long byteSize,
            Instant createdAt,
            Instant expiresAt
    ) {}

    public record BatchJobRecord(
            UUID jobId,
            UUID fileId,
            JobStatus status,
            Instant createdAt,
            Instant startedAt,
            Instant completedAt,
            Instant failedAt,
            int total,
            int completed,
            int failed,
            String resultS3Key,
            boolean downloadConsumed,
            String errorMessage,
            boolean highPriority,
            Instant expiresAt
    ) {
        public BatchJobRecord withStatus(JobStatus newStatus) {
            return new BatchJobRecord(
                    jobId, fileId, newStatus, createdAt, startedAt, completedAt, failedAt,
                    total, completed, failed, resultS3Key, downloadConsumed, errorMessage, highPriority, expiresAt
            );
        }

        public BatchJobRecord withStarted(Instant started) {
            return new BatchJobRecord(
                    jobId, fileId, status, createdAt, started, completedAt, failedAt,
                    total, completed, failed, resultS3Key, downloadConsumed, errorMessage, highPriority, expiresAt
            );
        }

        public BatchJobRecord withCompleted(int successCount, int failCount, String resultKey) {
            return new BatchJobRecord(
                    jobId, fileId, JobStatus.COMPLETED, createdAt, startedAt, Instant.now(), failedAt,
                    total, successCount, failCount, resultKey, downloadConsumed, errorMessage, highPriority, expiresAt
            );
        }

        public BatchJobRecord withFailed(String message) {
            return new BatchJobRecord(
                    jobId, fileId, JobStatus.FAILED, createdAt, startedAt, completedAt, Instant.now(),
                    total, completed, failed, resultS3Key, downloadConsumed, message, highPriority, expiresAt
            );
        }

        public BatchJobRecord withTotal(int newTotal) {
            return new BatchJobRecord(
                    jobId, fileId, status, createdAt, startedAt, completedAt, failedAt,
                    newTotal, completed, failed, resultS3Key, downloadConsumed, errorMessage, highPriority, expiresAt
            );
        }
    }
}

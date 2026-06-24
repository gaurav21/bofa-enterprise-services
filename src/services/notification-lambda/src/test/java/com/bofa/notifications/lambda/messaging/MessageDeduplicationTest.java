package com.bofa.notifications.lambda.messaging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MessageDeduplicationTest {

    @Mock private DataSource dataSource;
    @Mock private Connection connection;
    @Mock private PreparedStatement statement;
    @Mock private ResultSet resultSet;

    private MessageDeduplication deduplication;

    @BeforeEach
    void setUp() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        deduplication = new MessageDeduplication(dataSource);
    }

    @Test
    void tryClaimMessage_newMessageReturnsTrue() throws SQLException {
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(1);

        boolean result = deduplication.tryClaimMessage("msg-001", "FRAUD_ALERT", "ACC-123");

        assertTrue(result);
        verify(statement).setString(1, "msg-001");
        verify(statement).setString(2, "FRAUD_ALERT");
        verify(statement).setString(3, "ACC-123");
    }

    @Test
    void tryClaimMessage_duplicateMessageReturnsFalse() throws SQLException {
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(0);

        boolean result = deduplication.tryClaimMessage("msg-dup", "FRAUD_ALERT", "ACC-123");

        assertFalse(result);
    }

    @Test
    void tryClaimMessage_sqlExceptionReturnsFalse() throws SQLException {
        when(connection.prepareStatement(anyString())).thenThrow(new SQLException("DB error"));

        boolean result = deduplication.tryClaimMessage("msg-err", "FRAUD_ALERT", "ACC-123");

        assertFalse(result);
    }

    @Test
    void isProcessed_existingMessageReturnsTrue() throws SQLException {
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);

        boolean result = deduplication.isProcessed("msg-001");

        assertTrue(result);
    }

    @Test
    void isProcessed_unknownMessageReturnsFalse() throws SQLException {
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        boolean result = deduplication.isProcessed("msg-unknown");

        assertFalse(result);
    }

    @Test
    void isProcessed_sqlExceptionReturnsFalse() throws SQLException {
        when(connection.prepareStatement(anyString())).thenThrow(new SQLException("Connection lost"));

        boolean result = deduplication.isProcessed("msg-err");

        assertFalse(result);
    }
}

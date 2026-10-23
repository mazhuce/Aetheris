package com.aetheris.security.servlet;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.*;
import java.util.logging.Logger;

final class TestDataSource implements DataSource {
    @FunctionalInterface interface Connections {Connection open() throws SQLException;}
    private final Connections connections;
    TestDataSource(Connections connections){this.connections=connections;}
    public Connection getConnection() throws SQLException{return connections.open();}
    public Connection getConnection(String user,String password) throws SQLException{throw new SQLFeatureNotSupportedException();}
    public PrintWriter getLogWriter(){return null;}
    public void setLogWriter(PrintWriter writer){}
    public void setLoginTimeout(int seconds){}
    public int getLoginTimeout(){return 2;}
    public Logger getParentLogger(){return Logger.getGlobal();}
    public <T>T unwrap(Class<T> type) throws SQLException{throw new SQLFeatureNotSupportedException();}
    public boolean isWrapperFor(Class<?> type){return false;}
}

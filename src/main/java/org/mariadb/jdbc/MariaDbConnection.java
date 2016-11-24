/*
MariaDB Client for Java

Copyright (c) 2012-2014 Monty Program Ab.
Copyright (c) 2012-2016 MariaDB Corporation AB

This library is free software; you can redistribute it and/or modify it under
the terms of the GNU Lesser General Public License as published by the Free
Software Foundation; either version 2.1 of the License, or (at your option)
any later version.

This library is distributed in the hope that it will be useful, but
WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
for more details.

You should have received a copy of the GNU Lesser General Public License along
with this library; if not, write to Monty Program Ab info@montyprogram.com.

This particular MariaDB Client for Java file is work
derived from a Drizzle-JDBC. Drizzle-JDBC file which is covered by subject to
the following copyright and notice provisions:

Copyright (c) 2009-2011, Marcus Eriksson

Redistribution and use in source and binary forms, with or without modification,
are permitted provided that the following conditions are met:
Redistributions of source code must retain the above copyright notice, this list
of conditions and the following disclaimer.

Redistributions in binary form must reproduce the above copyright notice, this
list of conditions and the following disclaimer in the documentation and/or
other materials provided with the distribution.

Neither the name of the driver nor the names of its contributors may not be
used to endorse or promote products derived from this software without specific
prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS  AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY
OF SUCH DAMAGE.
*/

package org.mariadb.jdbc;

import org.mariadb.jdbc.internal.protocol.Protocol;
import org.mariadb.jdbc.internal.util.*;
import org.mariadb.jdbc.internal.util.dao.CallableStatementCacheKey;
import org.mariadb.jdbc.internal.util.dao.CloneableCallableStatement;
import org.mariadb.jdbc.internal.util.dao.QueryException;

import java.net.SocketException;
import java.sql.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public final class MariaDbConnection implements Connection {
    /**
     * Pattern  to check the correctness of callable statement query string
     * Legal queries, as documented in JDK have the form:
     * {[?=]call[(arg1,..,,argn)]}
     */
    private static Pattern CALLABLE_STATEMENT_PATTERN =
            Pattern.compile("^\\s*(\\?\\s*=)?(\\s*\\/\\*([^\\*]|\\*[^\\/])*\\*\\/)*\\s*call(\\s*\\/\\*([^\\*]|\\*[^\\/])*\\*\\/)*\\s*"
                            + "((((`[^`]+`)|([^`]+))\\.)?((`[^`]+`)|([^`\\(]+)))\\s*(\\(.*\\))?(\\s*\\/\\*([^\\*]|\\*[^\\/])*\\*\\/)*\\s*(#.*)?$",
                    Pattern.CASE_INSENSITIVE);
    /**
     * Check that query can be executed with PREPARE.
     */
    private static Pattern PREPARABLE_STATEMENT_PATTERN =
            Pattern.compile("^(\\s*\\/\\*([^\\*]|\\*[^\\/])*\\*\\/)*\\s*(SELECT|UPDATE|INSERT|DELETE|REPLACE|DO|CALL)",
                    Pattern.CASE_INSENSITIVE);
    public final ReentrantLock lock;
    /**
     * the protocol to communicate with.
     */
    private final Protocol protocol;
    private final ClientPrepareStatementCache clientPrepareStatementCache;
    public MariaDbPooledConnection pooledConnection;
    protected CallableStatementCache callableStatementCache;
    boolean noBackslashEscapes;
    boolean nullCatalogMeansCurrent = true;
    int autoIncrementIncrement;
    volatile int lowercaseTableNames = -1;

    /**
     * save point count - to generate good names for the savepoints.
     */
    private int savepointCount = 0;
    /**
     * the properties for the client.
     */
    private Options options;
    private boolean warningsCleared;

    /**
     * Creates a new connection with a given protocol and query factory.
     *
     * @param protocol the protocol to use.
     */
    private MariaDbConnection(Protocol protocol, ReentrantLock lock) throws SQLException {
        this.protocol = protocol;
        options = protocol.getOptions();
        noBackslashEscapes = protocol.noBackslashEscapes();
        nullCatalogMeansCurrent = options.nullCatalogMeansCurrent;
        if (options.cacheCallableStmts) {
            callableStatementCache = CallableStatementCache.newInstance(options.callableStmtCacheSize);
        }
        this.lock = lock;

        if (options.cachePrepStmts) {
            this.clientPrepareStatementCache = ClientPrepareStatementCache.newInstance(options.prepStmtCacheSize);
        } else {
            clientPrepareStatementCache = null;
        }
    }

    public static MariaDbConnection newConnection(Protocol protocol, ReentrantLock lock) throws SQLException {
        return new MariaDbConnection(protocol, lock);
    }

    public static String quoteIdentifier(String string) {
        return "`" + string.replaceAll("`", "``") + "`";
    }

    /**
     * UnQuote string.
     *
     * @param string value
     * @return unquote string
     * @deprecated since 1.3.0
     */
    @Deprecated
    public static String unquoteIdentifier(String string) {
        if (string != null && string.startsWith("`") && string.endsWith("`") && string.length() >= 2) {
            return string.substring(1, string.length() - 1).replace("``", "`");
        }
        return string;
    }

    Protocol getProtocol() {
        return protocol;
    }

    int getAutoIncrementIncrement() {
        if (autoIncrementIncrement == 0) {
            try {
                ResultSet rs = createStatement().executeQuery("select @@auto_increment_increment");
                rs.next();
                autoIncrementIncrement = rs.getInt(1);
            } catch (SQLException e) {
                autoIncrementIncrement = 1;
            }
        }
        return autoIncrementIncrement;
    }

    /**
     * creates a new statement.
     *
     * @return a statement
     * @throws SQLException if we cannot create the statement.
     */
    public Statement createStatement() throws SQLException {
        checkConnection();
        return new MariaDbStatement(this, ResultSet.TYPE_FORWARD_ONLY);
    }

    /**
     * Creates a <code>Statement</code> object that will generate <code>ResultSet</code> objects with the given type
     * and concurrency. This method is the same as the <code>createStatement</code> method above, but it allows the
     * default result set type and concurrency to be overridden.
     * The holdability of the created result sets can be determined by calling {@link #getHoldability}.
     *
     * @param resultSetType        a result set type; one of <code>ResultSet.TYPE_FORWARD_ONLY</code>,
     *                             <code>ResultSet.TYPE_SCROLL_INSENSITIVE</code>, or
     *                             <code>ResultSet.TYPE_SCROLL_SENSITIVE</code>
     * @param resultSetConcurrency a concurrency type; one of <code>ResultSet.CONCUR_READ_ONLY</code> or
     *                             <code>ResultSet.CONCUR_UPDATABLE</code>
     * @return a new <code>Statement</code> object that will generate <code>ResultSet</code> objects with the given
     *         type and concurrency
     * @throws SQLException if a database access error occurs, this method is called on a closed connection or the given
     *                      parameters are not <code>ResultSet</code> constants indicating type and concurrency
     */
    public Statement createStatement(final int resultSetType, final int resultSetConcurrency) throws SQLException {
        return new MariaDbStatement(this, resultSetType);
    }

    /**
     * Creates a <code>Statement</code> object that will generate <code>ResultSet</code> objects with the given type,
     * concurrency, and holdability.
     * This method is the same as the <code>createStatement</code> method above, but it allows the default result set
     * type, concurrency, and holdability to be overridden.
     *
     * @param resultSetType        one of the following <code>ResultSet</code> constants:
     *                             <code>ResultSet.TYPE_FORWARD_ONLY</code>,
     *                             <code>ResultSet.TYPE_SCROLL_INSENSITIVE</code>, or
     *                             <code>ResultSet.TYPE_SCROLL_SENSITIVE</code>
     * @param resultSetConcurrency one of the following <code>ResultSet</code> constants:
     *                             <code>ResultSet.CONCUR_READ_ONLY</code> or
     *                             <code>ResultSet.CONCUR_UPDATABLE</code>
     * @param resultSetHoldability one of the following <code>ResultSet</code> constants:
     *                             <code>ResultSet.HOLD_CURSORS_OVER_COMMIT</code> or
     *                             <code>ResultSet.CLOSE_CURSORS_AT_COMMIT</code>
     * @return a new <code>Statement</code> object that will generate <code>ResultSet</code> objects with the given
     * type, concurrency, and holdability
     * @throws SQLException                    if a database access error occurs, this method is called on a closed
     *                                         connection or the given parameters are not <code>ResultSet</code>
     *                                         constants indicating type, concurrency, and holdability
     * @throws SQLFeatureNotSupportedException if the JDBC driver does not support this method or this method is not
     *                                         supported for the specified result set type, result set holdability
     *                                         and result set concurrency.
     * @see ResultSet
     * @since 1.4
     */
    public Statement createStatement(final int resultSetType, final int resultSetConcurrency, final int resultSetHoldability)
            throws SQLException {
        if (resultSetConcurrency != ResultSet.CONCUR_READ_ONLY) {
            throw ExceptionMapper.getFeatureNotSupportedException("Only read-only result sets allowed");
        }

        return createStatement();
    }

    private void checkConnection() throws SQLException {
        if (protocol.isExplicitClosed()) {
            throw new SQLException("createStatement() is called on closed connection");
        }
        if (protocol.isClosed() && protocol.getProxy() != null) {
            lock.lock();
            try {
                protocol.getProxy().reconnect();
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * creates a new client prepared statement.
     *
     * @param sql the query.
     * @return a prepared statement.
     * @throws SQLException if there is a problem preparing the statement.
     */
    protected MariaDbClientPreparedStatement clientPrepareStatement(final String sql) throws SQLException {
        return new MariaDbClientPreparedStatement(this, sql, ResultSet.TYPE_FORWARD_ONLY);
    }

    /**
     * creates a new prepared statement.
     *
     * @param sql the query.
     * @return a prepared statement.
     * @throws SQLException if there is a problem preparing the statement.
     */
    public PreparedStatement prepareStatement(final String sql) throws SQLException {
        return internalPrepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY);
    }


    /**
     * Creates a <code>PreparedStatement</code> object that will generate <code>ResultSet</code> objects with the given
     * type and concurrency. This method is the same as the <code>prepareStatement</code> method above, but it allows
     * the default result set type and concurrency to be overridden. The holdability of the created result sets can be
     * determined by calling {@link #getHoldability}.
     *
     * @param sql                  a <code>String</code> object that is the SQL statement to be sent to the database;
     *                             may contain one or more '?' IN parameters
     * @param resultSetType        a result set type; one of <code>ResultSet.TYPE_FORWARD_ONLY</code>,
     *                             <code>ResultSet.TYPE_SCROLL_INSENSITIVE</code>, or
     *                             <code>ResultSet.TYPE_SCROLL_SENSITIVE</code>
     * @param resultSetConcurrency a concurrency type; one of <code>ResultSet.CONCUR_READ_ONLY</code> or
     *                             <code>ResultSet.CONCUR_UPDATABLE</code>
     * @return a new PreparedStatement object containing the pre-compiled SQL statement that will produce
     * <code>ResultSet</code> objects with the given type and concurrency
     * @throws SQLException if a database access error occurs, this method is called on a closed connection or the
     *                      given parameters are not<code>ResultSet</code> constants indicating type and concurrency
     */
    public PreparedStatement prepareStatement(final String sql, final int resultSetType, final int resultSetConcurrency)
            throws SQLException {
        return internalPrepareStatement(sql, resultSetType);
    }

    /**
     * <p>Creates a <code>PreparedStatement</code> object that will generate <code>ResultSet</code> objects with the
     * given type, concurrency, and holdability.</p>
     * <p>This method is the same as the <code>prepareStatement</code> method above, but it allows the default result
     * set type, concurrency, and holdability to be overridden.</p>
     *
     * @param sql                  a <code>String</code> object that is the SQL statement to be sent to the database;
     *                             may contain one or more '?' IN parameters
     * @param resultSetType        one of the following <code>ResultSet</code> constants:
     *                             <code>ResultSet.TYPE_FORWARD_ONLY</code>,
     *                             <code>ResultSet.TYPE_SCROLL_INSENSITIVE</code>, or
     *                             <code>ResultSet.TYPE_SCROLL_SENSITIVE</code>
     * @param resultSetConcurrency one of the following <code>ResultSet</code> constants:
     *                             <code>ResultSet.CONCUR_READ_ONLY</code> or
     *                             <code>ResultSet.CONCUR_UPDATABLE</code>
     * @param resultSetHoldability one of the following <code>ResultSet</code> constants:
     *                             <code>ResultSet.HOLD_CURSORS_OVER_COMMIT</code> or
     *                             <code>ResultSet.CLOSE_CURSORS_AT_COMMIT</code>
     * @return a new <code>PreparedStatement</code> object, containing the pre-compiled SQL statement, that will
     * generate <code>ResultSet</code> objects with the given type, concurrency, and holdability
     * @throws SQLException                    if a database access error occurs, this method is called on a closed
     *                                         connection or the given parameters are not
     *                                         <code>ResultSet</code> constants indicating type, concurrency, and
     *                                         holdability
     * @throws SQLFeatureNotSupportedException if the JDBC driver does not support this method or this method is not
     *                                         supported for the specified result set type, result set holdability and
     *                                         result set concurrency.
     * @see ResultSet
     * @since 1.4
     */
    public PreparedStatement prepareStatement(final String sql,
                                              final int resultSetType,
                                              final int resultSetConcurrency,
                                              final int resultSetHoldability) throws SQLException {
        if (resultSetConcurrency != ResultSet.CONCUR_READ_ONLY) {
            throw ExceptionMapper.getFeatureNotSupportedException("Only read-only result sets allowed");
        }
        //TODO : implement parameters
        // resultSetType is ignored since we always are scroll insensitive
        return prepareStatement(sql);
    }

    /**
     * <p>Creates a default <code>PreparedStatement</code> object that has the capability to retrieve auto-generated keys. The given constant tells
     * the driver whether it should make auto-generated keys available for retrieval.  This parameter is ignored if the SQL statement is not an
     * <code>INSERT</code> statement, or an SQL statement able to return auto-generated keys (the list of such statements is vendor-specific).</p>
     * <p><B>Note:</B> This method is optimized for handling parametric SQL statements that benefit from precompilation. If the driver supports
     * precompilation, the method <code>prepareStatement</code> will send the statement to the database for precompilation. Some drivers may not
     * support precompilation. In this case, the statement may not be sent to the database until the <code>PreparedStatement</code> object is
     * executed.  This has no direct effect on users; however, it does affect which methods throw certain SQLExceptions.</p>
     * <p>Result sets created using the returned <code>PreparedStatement</code> object will by default be type <code>TYPE_FORWARD_ONLY</code> and
     * have a concurrency level of <code>CONCUR_READ_ONLY</code>. The holdability of the created result sets can be determined by calling {@link
     * #getHoldability}.</p>
     *
     * @param sql               an SQL statement that may contain one or more '?' IN parameter placeholders
     * @param autoGeneratedKeys a flag indicating whether auto-generated keys should be returned; one of
     *                          <code>Statement.RETURN_GENERATED_KEYS</code>
     *                          or <code>Statement.NO_GENERATED_KEYS</code>
     * @return a new <code>PreparedStatement</code> object, containing the pre-compiled SQL statement, that will have
     * the capability of returning auto-generated keys
     * @throws SQLException                    if a database access error occurs, this method is called on a closed
     *                                         connection or the given parameter is not a <code>Statement</code>
     *                                         constant indicating whether auto-generated keys should be returned
     * @throws SQLFeatureNotSupportedException if the JDBC driver does not support this method with a constant of
     *                                         Statement.RETURN_GENERATED_KEYS
     * @since 1.4
     */
    public PreparedStatement prepareStatement(final String sql, final int autoGeneratedKeys) throws SQLException {
        return internalPrepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY);
    }

    /**
     * <p>Creates a default <code>PreparedStatement</code> object capable of returning the auto-generated keys designated by the given array. This
     * array contains the indexes of the columns in the target table that contain the auto-generated keys that should be made available.  The driver
     * will ignore the array if the SQL statement is not an <code>INSERT</code> statement, or an SQL statement able to return auto-generated keys
     * (the list of such statements is vendor-specific).</p>
     * <p>An SQL statement with or without IN parameters can be pre-compiled and stored in a <code>PreparedStatement</code> object. This object can
     * then be used to efficiently execute this statement multiple times.</p>
     * <p><B>Note:</B> This method is optimized for handling parametric SQL statements that benefit from precompilation. If the driver supports
     * precompilation, the method <code>prepareStatement</code> will send the statement to the database for precompilation. Some drivers may not
     * support precompilation. In this case, the statement may not be sent to the database until the <code>PreparedStatement</code> object is
     * executed.  This has no direct effect on users; however, it does affect which methods throw certain SQLExceptions.</p>
     * <p>
     * Result sets created using the returned <code>PreparedStatement</code> object will by default be type <code>TYPE_FORWARD_ONLY</code> and have a
     * concurrency level of <code>CONCUR_READ_ONLY</code>. The holdability of the created result sets can be determined by calling {@link
     * #getHoldability}.</p>
     *
     * @param sql           an SQL statement that may contain one or more '?' IN parameter placeholders
     * @param columnIndexes an array of column indexes indicating the columns that should be returned from the inserted row or rows
     * @return a new <code>PreparedStatement</code> object, containing the pre-compiled statement, that is capable of returning the auto-generated
     * keys designated by the given array of column indexes
     * @throws SQLException                    if a database access error occurs or this method is called on a closed connection
     * @throws SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @since 1.4
     */
    public PreparedStatement prepareStatement(final String sql, final int[] columnIndexes) throws SQLException {
        return prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
    }

    /**
     * <p>Creates a default <code>PreparedStatement</code> object capable of returning the auto-generated keys designated by the given array.
     * This array contains the names of the columns in the target table that contain the auto-generated keys that should be returned. The driver will
     * ignore the array if the SQL statement is not an <code>INSERT</code> statement, or an SQL statement able to return auto-generated keys
     * (the list of such statements is vendor-specific).</p>
     * <p>An SQL statement with or without IN parameters can be pre-compiled and stored in a <code>PreparedStatement</code> object. This object can
     * then be used to efficiently execute this statement multiple times.</p>
     * <p><B>Note:</B> This method is optimized for handling parametric SQL statements that benefit from precompilation. If the driver supports
     * precompilation, the method <code>prepareStatement</code> will send the statement to the database for precompilation. Some drivers may not
     * support precompilation. In this case, the statement may not be sent to the database until the <code>PreparedStatement</code> object is
     * executed.  This has no direct effect on users; however, it does affect which methods throw certain SQLExceptions.</p>
     * <p>Result sets created using the returned <code>PreparedStatement</code> object will by default be type <code>TYPE_FORWARD_ONLY</code> and
     * have a concurrency level of <code>CONCUR_READ_ONLY</code>. The holdability of the created result sets can be determined by calling {@link
     * #getHoldability}.</p>
     *
     * @param sql         an SQL statement that may contain one or more '?' IN parameter placeholders
     * @param columnNames an array of column names indicating the columns that should be returned from the inserted row or rows
     * @return a new <code>PreparedStatement</code> object, containing the pre-compiled statement, that is capable of returning the auto-generated
     * keys designated by the given array of column names
     * @throws SQLException                    if a database access error occurs or this method is called on a closed connection
     * @throws SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @since 1.4
     */
    public PreparedStatement prepareStatement(final String sql, final String[] columnNames) throws SQLException {
        return prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
    }


    /**
     * Send ServerPrepareStatement or ClientPrepareStatement depending on SQL query and options
     * If server side and PREPARE can be delayed, a facade will be return, to have a fallback on client prepareStatement.
     *
     * @param sql                 sql query
     * @param resultSetScrollType one of the following <code>ResultSet</code> constants: <code>ResultSet.TYPE_FORWARD_ONLY</code>,
     *                            <code>ResultSet.TYPE_SCROLL_INSENSITIVE</code>, or <code>ResultSet.TYPE_SCROLL_SENSITIVE</code>
     * @return PrepareStatement
     * @throws SQLException if a connection error occur during the server preparation.
     */
    public PreparedStatement internalPrepareStatement(final String sql, final int resultSetScrollType)
            throws SQLException {

        if (sql != null) {

            String sqlQuery = Utils.nativeSql(sql, noBackslashEscapes);

            if (options.useServerPrepStmts && PREPARABLE_STATEMENT_PATTERN.matcher(sqlQuery).find()) {
                //prepare isn't delayed -> if prepare fail, fallback to client preparedStatement?
                checkConnection();
                try {
                    return new MariaDbServerPreparedStatement(this, sqlQuery, resultSetScrollType, true);
                } catch (SQLNonTransientConnectionException e) {
                    throw e;
                } catch (SQLException e) {
                    //on some specific case, server cannot prepared data (CONJ-238)
                    //will use clientPreparedStatement
                }
            }
            return new MariaDbClientPreparedStatement(this, sqlQuery, resultSetScrollType);
        } else {
            throw new SQLException("SQL value can not be NULL");
        }
    }

    /**
     * Creates a <code>CallableStatement</code> object for calling
     * database stored procedures.
     * The <code>CallableStatement</code> object provides
     * methods for setting up its IN and OUT parameters, and
     * methods for executing the call to a stored procedure.
     * example : {?= call &lt;procedure-name&gt;[(&lt;arg1&gt;,&lt;arg2&gt;, ...)]}
     * or {call &lt;procedure-name&gt;[(&lt;arg1&gt;,&lt;arg2&gt;, ...)]}
     * <p>
     * <b>Note:</b> This method is optimized for handling stored
     * procedure call statements.
     * </p>
     *
     * @param sql an SQL statement that may contain one or more '?'
     *            parameter placeholders. Typically this statement is specified using JDBC
     *            call escape syntax.
     * @return a new default <code>CallableStatement</code> object containing the
     * pre-compiled SQL statement
     * @throws SQLException if a database access error occurs
     *                      or this method is called on a closed connection
     */
    public CallableStatement prepareCall(final String sql) throws SQLException {
        checkConnection();

        String query = Utils.nativeSql(sql, noBackslashEscapes);
        Matcher matcher = CALLABLE_STATEMENT_PATTERN.matcher(query);
        if (!matcher.matches()) {
            throw new SQLSyntaxErrorException(
                    "invalid callable syntax. must be like {? = call <procedure/function name>[(?,?, ...)]}\n but was : "
                            + query);
        }
        boolean isFunction = (matcher.group(1) != null);
        String databaseAndProcedure = matcher.group(6);
        String database = matcher.group(8);
        String procedureName = matcher.group(11);
        String arguments = matcher.group(14);

        if (database != null && options.cacheCallableStmts) {
            if (callableStatementCache.containsKey(new CallableStatementCacheKey(database, query))) {
                //Clone to avoid side effect like having some open resultset.
                try {
                    CallableStatement callableStatement = callableStatementCache.get(new CallableStatementCacheKey(getDatabase(), query));
                    if (callableStatement != null) {
                        return ((CloneableCallableStatement) callableStatement).clone();
                    }
                } catch (CloneNotSupportedException cloneNotSupportedException) {
                    cloneNotSupportedException.printStackTrace();
                }
            }
            CallableStatement callableStatement = createNewCallableStatement(query, procedureName, isFunction, databaseAndProcedure, database,
                    arguments);
            callableStatementCache.put(new CallableStatementCacheKey(database, query), callableStatement);
            return callableStatement;
        }
        return createNewCallableStatement(query, procedureName, isFunction, databaseAndProcedure, database, arguments);
    }


    /**
     * Creates a <code>CallableStatement</code> object that will generate <code>ResultSet</code> objects with the given
     * type and concurrency. This method is the same as the <code>prepareCall</code> method above, but it allows the
     * default result set type and concurrency to be overridden.
     * The holdability of the created result sets can be determined by calling {@link #getHoldability}.
     *
     * @param sql                  a <code>String</code> object that is the SQL statement to be sent to the database;
     *                             may contain on or more '?' parameters
     * @param resultSetType        a result set type; one of <code>ResultSet.TYPE_FORWARD_ONLY</code>,
     *                             <code>ResultSet.TYPE_SCROLL_INSENSITIVE</code>, or
     *                             <code>ResultSet.TYPE_SCROLL_SENSITIVE</code>
     * @param resultSetConcurrency a concurrency type; one of <code>ResultSet.CONCUR_READ_ONLY</code> or
     *                             <code>ResultSet.CONCUR_UPDATABLE</code>
     * @return a new <code>CallableStatement</code> object containing the pre-compiled SQL statement that will produce
     * <code>ResultSet</code> objects with the given type and concurrency
     * @throws SQLException                    if a database access error occurs, this method is called on a closed
     *                                         connection or the given parameters are not
     *                                         <code>ResultSet</code> constants indicating type and concurrency
     * @throws SQLFeatureNotSupportedException if the JDBC driver does not support this method or this method is not
     *                                         supported for the
     *                                         specified result set type and result set concurrency.
     */
    public CallableStatement prepareCall(final String sql, final int resultSetType, final int resultSetConcurrency) throws SQLException {
        return prepareCall(sql);
    }

    /**
     * Creates a <code>CallableStatement</code> object that will generate <code>ResultSet</code> objects with the given
     * type and concurrency. This method is the same as the <code>prepareCall</code> method above, but it allows the
     * default result set type, result set concurrency type and holdability to be overridden.
     *
     * @param sql                  a <code>String</code> object that is the SQL statement to be sent to the database;
     *                             may contain on or more '?' parameters
     * @param resultSetType        one of the following <code>ResultSet</code> constants:
     *                             <code>ResultSet.TYPE_FORWARD_ONLY</code>,
     *                             <code>ResultSet.TYPE_SCROLL_INSENSITIVE</code>, or
     *                             <code>ResultSet.TYPE_SCROLL_SENSITIVE</code>
     * @param resultSetConcurrency one of the following <code>ResultSet</code> constants:
     *                             <code>ResultSet.CONCUR_READ_ONLY</code> or
     *                             <code>ResultSet.CONCUR_UPDATABLE</code>
     * @param resultSetHoldability one of the following <code>ResultSet</code> constants:
     *                             <code>ResultSet.HOLD_CURSORS_OVER_COMMIT</code> or
     *                             <code>ResultSet.CLOSE_CURSORS_AT_COMMIT</code>
     * @return a new <code>CallableStatement</code> object, containing the pre-compiled SQL statement, that will
     * generate <code>ResultSet</code> objects with the given type, concurrency, and holdability
     * @throws SQLException                    if a database access error occurs, this method is called on a closed
     *                                         connection or the given parameters are not
     *                                         <code>ResultSet</code> constants indicating type, concurrency, and
     *                                         holdability
     * @throws SQLFeatureNotSupportedException if the JDBC driver does not support this method or this method is not
     *                                         supported for the specified result set type, result set holdability and
     *                                         result set concurrency.
     * @see ResultSet
     * @since 1.4
     */
    public CallableStatement prepareCall(final String sql,
                                         final int resultSetType,
                                         final int resultSetConcurrency,
                                         final int resultSetHoldability) throws SQLException {
        return prepareCall(sql);
    }

    private CallableStatement createNewCallableStatement(String query, String procedureName, boolean isFunction, String databaseAndProcedure,
                                                         String database, String arguments) throws SQLException {
        if (arguments == null) {
            arguments = "()";
        }
        if (isFunction) {
            return new MariaDbFunctionStatement(this, database, databaseAndProcedure, arguments);
        } else {
            return new MariaDbProcedureStatement(query, this, procedureName, database);
        }
    }

    @Override
    public String nativeSQL(final String sql) throws SQLException {
        return Utils.nativeSql(sql, noBackslashEscapes);
    }

    /**
     * returns true if statements on this connection are auto commited.
     *
     * @return true if auto commit is on.
     * @throws SQLException if there is an error
     */
    public boolean getAutoCommit() throws SQLException {
        return protocol.getAutocommit();
    }

    /**
     * Sets whether this connection is auto commited.
     *
     * @param autoCommit if it should be auto commited.
     * @throws SQLException if something goes wrong talking to the server.
     */
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        if (autoCommit == getAutoCommit()) {
            return;
        }
        Statement stmt = createStatement();
        try {
            stmt.executeUpdate("set autocommit=" + ((autoCommit) ? "1" : "0"));
        } finally {
            stmt.close();
        }
    }

    /**
     * Sends commit to the server.
     *
     * @throws SQLException if there is an error commiting.
     */
    public void commit() throws SQLException {
        lock.lock();
        try {
            if (!getAutoCommit()) {
                Statement st = createStatement();
                try {
                    st.execute("COMMIT");
                } finally {
                    st.close();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Rolls back a transaction.
     *
     * @throws SQLException if there is an error rolling back.
     */
    public void rollback() throws SQLException {
        Statement st = createStatement();
        try {
            st.execute("ROLLBACK");
        } finally {
            st.close();
        }
    }

    /**
     * <p>Undoes all changes made after the given <code>Savepoint</code> object was set.</p>
     * <p>This method should be used only when auto-commit has been disabled.</p>
     *
     * @param savepoint the <code>Savepoint</code> object to roll back to
     * @throws SQLException                    if a database access error occurs, this method is called while
     *                                         participating in a distributed transaction, this
     *                                         method is called on a closed connection, the <code>Savepoint</code>
     *                                         object is no longer valid, or this <code>Connection</code> object is
     *                                         currently in auto-commit mode
     * @throws SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see Savepoint
     * @see #rollback
     * @since 1.4
     */
    public void rollback(final Savepoint savepoint) throws SQLException {
        Statement st = createStatement();
        st.execute("ROLLBACK TO SAVEPOINT " + savepoint.toString());
        st.close();
    }

    /**
     * close the connection.
     *
     * @throws SQLException if there is a problem talking to the server.
     */
    public void close() throws SQLException {
        if (pooledConnection != null) {
            lock.lock();
            try {
                if (protocol != null && protocol.inTransaction()) {
                    /* Rollback transaction prior to returning physical connection to the pool */
                    rollback();
                }
            } finally {
                lock.unlock();
            }
            pooledConnection.fireConnectionClosed();
            return;
        }
        protocol.closeExplicit();
    }

    /**
     * checks if the connection is closed.
     *
     * @return true if the connection is closed
     * @throws SQLException if the connection cannot be closed.
     */
    public boolean isClosed() throws SQLException {
        return protocol.isClosed();
    }

    /**
     * returns the meta data about the database.
     *
     * @return meta data about the db.
     * @throws SQLException if there is a problem creating the meta data.
     */
    public DatabaseMetaData getMetaData() throws SQLException {
        return new MariaDbDatabaseMetaData(this, protocol.getUsername(),
                "jdbc:mysql://" + protocol.getHost() + ":" + protocol.getPort() + "/" + protocol.getDatabase());
    }

    /**
     * Retrieves whether this <code>Connection</code> object is in read-only mode.
     *
     * @return <code>true</code> if this <code>Connection</code> object is read-only; <code>false</code> otherwise
     * @throws SQLException SQLException if a database access error occurs or this method is called on a closed connection
     */
    public boolean isReadOnly() throws SQLException {
        return protocol.getReadonly();
    }

    /**
     * Sets whether this connection is read only.
     *
     * @param readOnly true if it should be read only.
     * @throws SQLException if there is a problem
     */
    public void setReadOnly(final boolean readOnly) throws SQLException {
        try {
            protocol.setReadonly(readOnly);
        } catch (QueryException e) {
            ExceptionMapper.throwException(e, this, null);
        }
    }

    /**
     * <p>Retrieves this <code>Connection</code> object's current catalog name.</p>
     *
     * @return the current catalog name or <code>null</code> if there is none
     * @throws SQLException if a database access error occurs or this method is called on a closed connection
     * @see #setCatalog
     */
    public String getCatalog() throws SQLException {
        String catalog = null;
        Statement st = null;
        try {
            st = createStatement();
            ResultSet rs = st.executeQuery("select database()");
            rs.next();
            catalog = rs.getString(1);
        } finally {
            if (st != null) {
                st.close();
            }
        }
        return catalog;
    }

    /**
     * <p>Sets the given catalog name in order to select a subspace of this <code>Connection</code> object's database in which to work.</p>
     * <p>If the driver does not support catalogs, it will silently ignore this request.</p>
     * MySQL treats catalogs and databases as equivalent
     *
     * @param catalog the name of a catalog (subspace in this <code>Connection</code> object's database) in which to work
     * @throws SQLException if a database access error occurs or this method is called on a closed connection
     * @see #getCatalog
     */
    public void setCatalog(final String catalog) throws SQLException {
        if (catalog == null) {
            throw new SQLException("The catalog name may not be null", "XAE05");
        }
        try {
            protocol.setCatalog(catalog);
        } catch (QueryException e) {
            ExceptionMapper.throwException(e, this, null);
        }
    }

    /**
     * Retrieves this <code>Connection</code> object's current transaction isolation level.
     *
     * @return the current transaction isolation level, which will be one of the following constants:
     * <code>Connection.TRANSACTION_READ_UNCOMMITTED</code>,
     * <code>Connection.TRANSACTION_READ_COMMITTED</code>, <code>Connection.TRANSACTION_REPEATABLE_READ</code>,
     * <code>Connection.TRANSACTION_SERIALIZABLE</code>, or <code>Connection.TRANSACTION_NONE</code>.
     * @throws SQLException if a database access error occurs or this method is called on a closed connection
     * @see #setTransactionIsolation
     */
    public int getTransactionIsolation() throws SQLException {
        final Statement stmt = createStatement();
        try {
            final ResultSet rs = stmt.executeQuery("SELECT @@tx_isolation");
            rs.next();
            final String response = rs.getString(1);
            if (response.equals("REPEATABLE-READ")) {
                return Connection.TRANSACTION_REPEATABLE_READ;
            }
            if (response.equals("READ-UNCOMMITTED")) {
                return Connection.TRANSACTION_READ_UNCOMMITTED;
            }
            if (response.equals("READ-COMMITTED")) {
                return Connection.TRANSACTION_READ_COMMITTED;
            }
            if (response.equals("SERIALIZABLE")) {
                return Connection.TRANSACTION_SERIALIZABLE;
            }
        } finally {
            stmt.close();
        }
        throw ExceptionMapper.getSqlException("Could not get transaction isolation level");
    }

    /**
     * <p>Attempts to change the transaction isolation level for this <code>Connection</code> object to the one given.
     * The constants defined in the interface <code>Connection</code> are the possible transaction isolation levels.</p>
     * <p><B>Note:</B> If this method is called during a transaction, the result is implementation-defined.</p>
     *
     * @param level one of the following <code>Connection</code> constants:
     *              <code>Connection.TRANSACTION_READ_UNCOMMITTED</code>,
     *              <code>Connection.TRANSACTION_READ_COMMITTED</code>,
     *              <code>Connection.TRANSACTION_REPEATABLE_READ</code>, or
     *              <code>Connection.TRANSACTION_SERIALIZABLE</code>.
     *              (Note that <code>Connection.TRANSACTION_NONE</code> cannot be used because it specifies that
     *              transactions are not supported.)
     * @throws SQLException if a database access error occurs, this method is called on a closed connection or the given
     *                      parameter is not one
     *                      of the <code>Connection</code> constants
     * @see DatabaseMetaData#supportsTransactionIsolationLevel
     * @see #getTransactionIsolation
     */
    public void setTransactionIsolation(final int level) throws SQLException {
        try {
            protocol.setTransactionIsolation(level);
        } catch (QueryException e) {
            ExceptionMapper.throwException(e, this, null);
        }
    }

    /**
     * <p>Retrieves the first warning reported by calls on this <code>Connection</code> object.  If there is more than one warning, subsequent
     * warnings will be chained to the first one and can be retrieved by calling the method <code>SQLWarning.getNextWarning</code> on the warning
     * that was retrieved previously.</p>
     * <p>This method may not be called on a closed connection; doing so will cause an <code>SQLException</code> to be thrown.</p>
     * <p><B>Note:</B> Subsequent warnings will be chained to this SQLWarning.</p>
     *
     * @return the first <code>SQLWarning</code> object or <code>null</code> if there are none
     * @throws SQLException if a database access error occurs or this method is called on a closed connection
     * @see SQLWarning
     */
    public SQLWarning getWarnings() throws SQLException {
        if (warningsCleared || isClosed() || !protocol.hasWarnings()) {
            return null;
        }
        Statement st = null;
        ResultSet rs = null;
        SQLWarning last = null;
        SQLWarning first = null;
        try {
            st = this.createStatement();
            rs = st.executeQuery("show warnings");
            // returned result set has 'level', 'code' and 'message' columns, in this order.
            while (rs.next()) {
                int code = rs.getInt(2);
                String message = rs.getString(3);
                SQLWarning warning = new SQLWarning(message, ExceptionMapper.mapCodeToSqlState(code), code);
                if (first == null) {
                    first = warning;
                    last = warning;
                } else {
                    last.setNextWarning(warning);
                    last = warning;
                }
            }
        } finally {
            if (rs != null) {
                rs.close();
            }
            if (st != null) {
                st.close();
            }
        }
        return first;
    }

    /**
     * Clears all warnings reported for this <code>Connection</code> object. After a call to this method, the method <code>getWarnings</code> returns
     * <code>null</code> until a new warning is reported for this <code>Connection</code> object.
     *
     * @throws SQLException SQLException if a database access error occurs or this method is called on a closed connection
     */
    public void clearWarnings() throws SQLException {
        if (this.isClosed()) {
            throw ExceptionMapper.getSqlException("Connection.clearWarnings cannot be called on a closed connection");
        }
        warningsCleared = true;
    }

    /**
     * Reenable warnings, when next statement is executed.
     */
    public void reenableWarnings() {
        warningsCleared = false;
    }

    /**
     * Retrieves the <code>Map</code> object associated with this <code>Connection</code> object. Unless the application has added an entry, the type
     * map returned will be empty.
     *
     * @return the <code>java.util.Map</code> object associated with this <code>Connection</code> object
     * @throws SQLException                    if a database access error occurs or this method is called on a closed connection
     * @throws SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see #setTypeMap
     * @since 1.2
     */
    public Map<String, Class<?>> getTypeMap() throws SQLException {
        return null;
    }

    /**
     * Installs the given <code>TypeMap</code> object as the type map for this <code>Connection</code> object.
     * The type map will be used for the
     * custom mapping of SQL structured types and distinct types.
     *
     * @param map the <code>java.util.Map</code> object to install as the replacement for this <code>Connection</code>
     *            object's default type map
     * @throws SQLException                    if a database access error occurs, this method is called on a closed
     *                                         connection or the given parameter is not a
     *                                         <code>java.util.Map</code> object
     * @throws SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see #getTypeMap
     */
    public void setTypeMap(final Map<String, Class<?>> map) throws SQLException {
        throw ExceptionMapper.getFeatureNotSupportedException("Not yet supported");
    }

    /**
     * Retrieves the current holdability of <code>ResultSet</code> objects created using this <code>Connection</code>
     * object.
     *
     * @return the holdability, one of <code>ResultSet.HOLD_CURSORS_OVER_COMMIT</code> or
     *         <code>ResultSet.CLOSE_CURSORS_AT_COMMIT</code>
     * @throws SQLException if a database access error occurs or this method is called on a closed connection
     * @see #setHoldability
     * @see DatabaseMetaData#getResultSetHoldability
     * @see ResultSet
     * @since 1.4
     */
    public int getHoldability() throws SQLException {
        return ResultSet.HOLD_CURSORS_OVER_COMMIT;
    }

    /**
     * Changes the default holdability of <code>ResultSet</code> objects created using this <code>Connection</code>
     * object to the given holdability.
     * The default holdability of <code>ResultSet</code> objects can be be determined by invoking {@link
     * DatabaseMetaData#getResultSetHoldability}.
     *
     * @param holdability a <code>ResultSet</code> holdability constant; one of
     *                    <code>ResultSet.HOLD_CURSORS_OVER_COMMIT</code> or
     *                    <code>ResultSet.CLOSE_CURSORS_AT_COMMIT</code>
     * @throws SQLException                    if a database access occurs, this method is called on a closed
     *                                         connection, or the given parameter is not a
     *                                         <code>ResultSet</code> constant indicating holdability
     * @throws SQLFeatureNotSupportedException if the given holdability is not supported
     * @see #getHoldability
     * @see DatabaseMetaData#getResultSetHoldability
     * @see ResultSet
     */
    @Override
    public void setHoldability(final int holdability) throws SQLException {
    }

    /**
     * <p>Creates an unnamed savepoint in the current transaction and returns the new <code>Savepoint</code> object that
     * represents it.</p>
     * <p>if setSavepoint is invoked outside of an active transaction, a transaction will be started at this newly
     * created savepoint.</p>
     *
     * @return the new <code>Savepoint</code> object
     * @throws SQLException                    if a database access error occurs, this method is called while
     *                                         participating in a distributed transaction, this method is called on a
     *                                         closed connection or this <code>Connection</code> object is currently in
     *                                         auto-commit mode
     * @throws SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see Savepoint
     * @since 1.4
     */
    public Savepoint setSavepoint() throws SQLException {
        return setSavepoint("unnamed");
    }

    /**
     * <p>Creates a savepoint with the given name in the current transaction and returns the new <code>Savepoint</code>
     * object that represents it.</p>
     * if setSavepoint is invoked outside of an active transaction, a transaction will be started at this newly created
     * savepoint.
     *
     * @param name a <code>String</code> containing the name of the savepoint
     * @return the new <code>Savepoint</code> object
     * @throws SQLException                    if a database access error occurs, this method is called while
     *                                         participating in a distributed transaction, this method is called on a
     *                                         closed connection or this <code>Connection</code> object is currently in
     *                                         auto-commit mode
     * @throws SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see Savepoint
     * @since 1.4
     */
    public Savepoint setSavepoint(final String name) throws SQLException {
        Savepoint savepoint = new MariaDbSavepoint(name, savepointCount++);
        Statement st = createStatement();
        st.execute("SAVEPOINT " + savepoint.toString());
        return savepoint;

    }


    /**
     * Removes the specified <code>Savepoint</code>  and subsequent <code>Savepoint</code> objects from the current
     * transaction. Any reference to the savepoint after it have been removed will cause an <code>SQLException</code>
     * to be thrown.
     *
     * @param savepoint the <code>Savepoint</code> object to be removed
     * @throws SQLException                    if a database access error occurs, this method is called on a closed
     *                                         connection or the given <code>Savepoint</code> object is not a valid
     *                                         savepoint in the current transaction
     * @throws SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @since 1.4
     */
    public void releaseSavepoint(final Savepoint savepoint) throws SQLException {
        Statement st = createStatement();
        st.execute("RELEASE SAVEPOINT " + savepoint.toString());
        st.close();
    }

    /**
     * Constructs an object that implements the <code>Clob</code> interface. The object returned initially contains no
     * data. The <code>setAsciiStream</code>, <code>setCharacterStream</code> and <code>setString</code> methods of the
     * <code>Clob</code> interface may be used to add data to the <code>Clob</code>.
     *
     * @return An object that implements the <code>Clob</code> interface
     * @throws SQLException                    if an object that implements the <code>Clob</code> interface can not be
     *                                         constructed, this method is called on a
     *                                         closed connection or a database access error occurs.
     * @throws SQLFeatureNotSupportedException if the JDBC driver does not support this data type
     * @since 1.6
     */
    public Clob createClob() throws SQLException {
        return new MariaDbClob();
    }

    /**
     * Constructs an object that implements the <code>Blob</code> interface. The object returned initially contains no
     * data. The <code>setBinaryStream</code> and <code>setBytes</code> methods of the <code>Blob</code> interface may
     * be used to add data to the <code>Blob</code>.
     *
     * @return An object that implements the <code>Blob</code> interface
     * @throws SQLException                    if an object that implements the <code>Blob</code> interface can not be
     *                                         constructed, this method is called on a
     *                                         closed connection or a database access error occurs.
     * @throws SQLFeatureNotSupportedException if the JDBC driver does not support this data type
     * @since 1.6
     */
    public Blob createBlob() throws SQLException {
        return new MariaDbBlob();
    }

    /**
     * Constructs an object that implements the <code>NClob</code> interface. The object returned initially contains no
     * data. The <code>setAsciiStream</code>, <code>setCharacterStream</code> and <code>setString</code> methods of the
     * <code>NClob</code> interface may be used to add data to the <code>NClob</code>.
     *
     * @return An object that implements the <code>NClob</code> interface
     * @throws SQLException                    if an object that implements the <code>NClob</code> interface can not be
     *                                         constructed, this method is called on a
     *                                         closed connection or a database access error occurs.
     * @throws SQLFeatureNotSupportedException if the JDBC driver does not support this data type
     * @since 1.6
     */
    public NClob createNClob() throws SQLException {
        return new MariaDbClob();
    }

    /**
     * Constructs an object that implements the <code>SQLXML</code> interface. The object returned initially contains no
     * data. The <code>createXmlStreamWriter</code> object and <code>setString</code> method of the <code>SQLXML</code>
     * interface may be used to add data to the <code>SQLXML</code> object.
     *
     * @return An object that implements the <code>SQLXML</code> interface
     * @throws SQLException                    if an object that implements the <code>SQLXML</code> interface can not be
     *                                         constructed, this method is called on a
     *                                         closed connection or a database access error occurs.
     * @throws SQLFeatureNotSupportedException if the JDBC driver does not support this data type
     * @since 1.6
     */
    @Override
    public SQLXML createSQLXML() throws SQLException {
        throw ExceptionMapper.getFeatureNotSupportedException("Not supported");
    }

    /**
     * <p>Returns true if the connection has not been closed and is still valid. The driver shall submit a query on the
     * connection or use some other mechanism that positively verifies the connection is still valid when this method
     * is called.</p>
     * <p>The query submitted by the driver to validate the connection shall be executed in the context of the current
     * transaction.</p>
     *
     * @param timeout - The time in seconds to wait for the database operation used to validate the connection to
     *                complete.  If the timeout period expires before the operation completes, this method returns
     *                false.  A value of 0 indicates a timeout is not applied to thedatabase operation.
     * @return true if the connection is valid, false otherwise
     * @throws SQLException if the value supplied for <code>timeout</code> is less then 0
     * @see DatabaseMetaData#getClientInfoProperties
     * @since 1.6
     */
    public boolean isValid(final int timeout) throws SQLException {
        if (timeout < 0) {
            throw new SQLException("the value supplied for timeout is negative");
        }
        if (isClosed()) {
            return false;
        }
        try {
            return protocol.ping();
        } catch (QueryException e) {
            return false;
        }
    }

    /**
     * <p>Sets the value of the client info property specified by name to the value specified by value.</p>
     * <p>Applications may use the <code>DatabaseMetaData.getClientInfoProperties</code> method to determine the client info properties supported by
     * the driver and the maximum length that may be specified for each property.</p>
     * <p>The driver stores the value specified in a suitable location in the database.  For example in a special register, session parameter, or
     * system table column.  For efficiency the driver may defer setting the value in the database until the next time a statement is executed or
     * prepared. Other than storing the client information in the appropriate place in the database, these methods shall not alter the behavior of
     * the connection in anyway.  The values supplied to these methods are used for accounting, diagnostics and debugging purposes only.</p>
     * <p>The driver shall generate a warning if the client info name specified is not recognized by the driver.</p>
     * <p>If the value specified to this method is greater than the maximum length for the property the driver may either truncate the value and
     * generate a warning or generate a <code>SQLClientInfoException</code>.  If the driver generates a <code>SQLClientInfoException</code>, the
     * value specified was not set on the connection.</p>
     * <p>The following are standard client info properties.  Drivers are not required to support these properties however if the driver supports a
     * client info property that can be described by one of the standard properties, the standard property name should be used.</p>
     * <ul> <li>ApplicationName  -       The name of the application currently utilizing the connection</li> <li>ClientUser -       The
     * name of the user that the application using the connection is performing work for.  This may not be the same as the user name that was used in
     * establishing the connection.</li> <li>ClientHostname   -       The hostname of the computer the application using the connection is running
     * on.</li> </ul>
     *
     * @param name  The name of the client info property to set
     * @param value The value to set the client info property to.  If the value is null, the current value of the specified property is cleared.
     * @throws SQLClientInfoException if the database server returns an error while setting the client info value on the database server or
     *                                this method is called on a closed connection
     * @since 1.6
     */
    public void setClientInfo(final String name, final String value) throws SQLClientInfoException {
        if (protocol.isExplicitClosed()) {
            Map<String, ClientInfoStatus> failures = new HashMap<>();
            failures.put(name, ClientInfoStatus.REASON_UNKNOWN);
            throw new SQLClientInfoException("setClientInfo() is called on closed connection", failures);
        }

        if (protocol.isClosed() && protocol.getProxy() != null) {
            lock.lock();
            try {
                protocol.getProxy().reconnect();
            } catch (SQLException sqle) {
                Map<String, ClientInfoStatus> failures = new HashMap<>();
                failures.put(name, ClientInfoStatus.REASON_UNKNOWN);
                throw new SQLClientInfoException("Connection closed", failures, sqle);
            } finally {
                lock.unlock();
            }
        }

        if (name == null || (!"ApplicationName".equals(name)
                && !"ClientUser".equals(name)
                && !"ClientHostname".equals(name))) {
            Map<String, ClientInfoStatus> failures = new HashMap<>();
            failures.put(name, ClientInfoStatus.REASON_UNKNOWN_PROPERTY);
            throw new SQLClientInfoException("setClientInfo() parameters can only be \"ApplicationName\",\"ClientUser\" or \"ClientHostname\", "
                    + "but was : " + name, failures);
        }

        StringBuilder escapeQuery = new StringBuilder("SET @").append(name).append("=");
        if (value == null) {
            escapeQuery.append("null");
        } else {
            escapeQuery.append("'");
            int charsOffset = 0;
            int charsLength = value.length();
            char charValue;
            if (noBackslashEscapes) {
                do {
                    charValue = value.charAt(charsOffset);
                    if (charValue == '\'') escapeQuery.append('\''); //add a single escape quote
                    escapeQuery.append(charValue);
                    charsOffset++;
                } while (charsOffset < charsLength);
            } else {
                do {
                    charValue = value.charAt(charsOffset);
                    if (charValue == '\''
                            || charValue == '\\'
                            || charValue == '"'
                            || charValue == 0) escapeQuery.append('\\'); //add escape slash
                    escapeQuery.append(charValue);
                    charsOffset++;
                } while (charsOffset < charsLength);
            }
            escapeQuery.append("'");
        }

        try {
            Statement statement = createStatement();
            statement.execute(escapeQuery.toString());
        } catch (SQLException sqle) {
            Map<String, ClientInfoStatus> failures = new HashMap<>();
            failures.put(name, ClientInfoStatus.REASON_UNKNOWN);
            throw new SQLClientInfoException("unexpected error during setClientInfo", failures, sqle);

        }
    }


    /**
     * <p>Sets the value of the connection's client info properties.  The <code>Properties</code> object contains the
     * names and values of the client info properties to be set.  The set of client info properties contained in the
     * properties list replaces the current set of client info properties on the connection.  If a property that is
     * currently set on the connection is not present in the properties list, that property is
     * cleared. Specifying an empty properties list will clear all of the properties on the connection.  See
     * <code>setClientInfo (String, String)</code> for more information.</p>
     * <p>If an error occurs in setting any of the client info properties, a <code>SQLClientInfoException</code> is
     * thrown. The <code>SQLClientInfoException</code> contains information indicating which client info properties
     * were not set. The state of the client information is unknown because some databases do not allow multiple
     * client info properties to be set atomically.  For those databases, one or
     * more properties may have been set before the error occurred.</p>
     *
     * @param properties the list of client info properties to set
     * @throws SQLClientInfoException if the database server returns an error while setting the clientInfo values on the
     *                                database server or
     *                                this method is called on a closed connection
     * @see Connection#setClientInfo(String, String) setClientInfo(String, String)
     * @since 1.6
     */
    public void setClientInfo(final Properties properties) throws SQLClientInfoException {
        Map<String, ClientInfoStatus> propertiesExceptions = new HashMap<>();
        for (String name : new String[]{"ApplicationName", "ClientUser", "ClientHostname"}) {
            try {
                setClientInfo(name, properties.getProperty(name));
            } catch (SQLClientInfoException e) {
                propertiesExceptions.putAll(e.getFailedProperties());
            }
        }

        if (!propertiesExceptions.isEmpty()) {
            String errorMsg = "setClientInfo errors : the following properties where not set : " + propertiesExceptions.keySet();
            throw new SQLClientInfoException(errorMsg, propertiesExceptions);
        }
    }

    /**
     * Returns the value of the client info property specified by name.  This method may return null if the specified client info property has not
     * been set and does not have a default value.  This method will also return null if the specified client info property name is not supported by
     * the driver.
     * Applications may use the <code>DatabaseMetaData.getClientInfoProperties</code> method to determine the client info properties supported by the
     * driver.
     *
     * @param name The name of the client info property to retrieve
     * @return The value of the client info property specified
     * @throws SQLException if the database server returns an error when fetching the client info value from the database or this method is
     *                      called on a closed connection
     * @see DatabaseMetaData#getClientInfoProperties
     * @since 1.6
     */
    public String getClientInfo(final String name) throws SQLException {
        checkConnection();
        if (!"ApplicationName".equals(name) && !"ClientUser".equals(name) && !"ClientHostname".equals(name)) {
            throw new SQLException("name must be \"ApplicationName\", \"ClientUser\" or \"ClientHostname\", but was \"" + name + "\"");
        }
        try (Statement statement = createStatement()) {
            try (ResultSet rs = statement.executeQuery("SELECT @" + name)) {
                if (rs.next()) {
                    return rs.getString(1);
                }
            }
        }
        return null;
    }

    /**
     * Returns a list containing the name and current value of each client info property supported by the driver.  The value of a client info property
     * may be null if the property has not been set and does not have a default value.
     *
     * @return A <code>Properties</code> object that contains the name and current value of each of the client info properties supported by the
     * driver.
     * @throws SQLException if the database server returns an error when fetching the client info values from the database or this method is
     *                      called on a closed connection
     * @since 1.6
     */
    public Properties getClientInfo() throws SQLException {
        checkConnection();
        try (Statement statement = createStatement()) {
            try (ResultSet rs = statement.executeQuery("SELECT @ApplicationName, @ClientUser, @ClientHostname")) {
                if (rs.next()) {
                    Properties properties = new Properties();
                    if (rs.getString(1) != null) properties.setProperty("ApplicationName", rs.getString(1));
                    if (rs.getString(2) != null) properties.setProperty("ClientUser", rs.getString(2));
                    if (rs.getString(3) != null) properties.setProperty("ClientHostname", rs.getString(3));
                    return properties;
                }
            }
        }
        Properties properties = new Properties();
        properties.setProperty("ApplicationName", null);
        properties.setProperty("ClientUser", null);
        properties.setProperty("ClientHostname", null);
        return new Properties();
    }

    /**
     * Factory method for creating Array objects.
     * <b>Note: </b>When <code>createArrayOf</code> is used to create an array object that maps to a primitive data type, then it is
     * implementation-defined whether the <code>Array</code> object is an array of that primitive data type or an array of <code>Object</code>.
     * <b>Note: </b>The JDBC driver is responsible for mapping the elements <code>Object</code> array to the default JDBC SQL type defined in
     * java.sql.Types for the given class of <code>Object</code>. The default mapping is specified in Appendix B of the JDBC specification.  If the
     * resulting JDBC type is not the appropriate type for the given typeName then it is implementation defined whether an <code>SQLException</code>
     * is thrown or the driver supports the resulting conversion.
     *
     * @param typeName the SQL name of the type the elements of the array map to. The typeName is a database-specific name which may be the name of a
     *                 built-in type, a user-defined type or a standard  SQL type supported by this database. This is the value returned by
     *                 <code>Array.getBaseTypeName</code>
     * @param elements the elements that populate the returned object
     * @return an Array object whose elements map to the specified SQL type
     * @throws SQLException                    if a database error occurs, the JDBC type is not appropriate for the typeName and the conversion is not
     *                                         supported, the typeName is null or this method is called on a closed connection
     * @throws SQLFeatureNotSupportedException if the JDBC driver does not support this data type
     * @since 1.6
     */
    public Array createArrayOf(final String typeName, final Object[] elements) throws SQLException {
        throw ExceptionMapper.getFeatureNotSupportedException("Not yet supported");
    }

    /**
     * Factory method for creating Struct objects.
     *
     * @param typeName   the SQL type name of the SQL structured type that this <code>Struct</code> object maps to. The typeName is the name of  a
     *                   user-defined type that has been defined for this database. It is the value returned by <code>Struct.getSQLTypeName</code>.
     * @param attributes the attributes that populate the returned object
     * @return a Struct object that maps to the given SQL type and is populated with the given attributes
     * @throws SQLException                    if a database error occurs, the typeName is null or this method is called on a closed connection
     * @throws SQLFeatureNotSupportedException if the JDBC driver does not support this data type
     * @since 1.6
     */
    public Struct createStruct(final String typeName, final Object[] attributes) throws SQLException {
        throw ExceptionMapper.getFeatureNotSupportedException("Not yet supported");
    }

    /**
     * Returns an object that implements the given interface to allow access to non-standard methods, or standard methods not exposed by the proxy.
     * If the receiver implements the interface then the result is the receiver or a proxy for the receiver. If the receiver is a wrapper and the
     * wrapped object implements the interface then the result is the wrapped object or a proxy for the wrapped object. Otherwise return the the
     * result of calling <code>unwrap</code> recursively on the wrapped object or a proxy for that result. If the receiver is not a wrapper and does
     * not implement the interface, then an <code>SQLException</code> is thrown.
     *
     * @param iface A Class defining an interface that the result must implement.
     * @return an object that implements the interface. May be a proxy for the actual implementing object.
     * @throws SQLException If no object found that implements the interface
     * @since 1.6
     */
    public <T> T unwrap(final Class<T> iface) throws SQLException {
        return iface.cast(this);
    }

    /**
     * Returns true if this either implements the interface argument or is directly or indirectly a wrapper for an object that does. Returns false
     * otherwise. If this implements the interface then return true, else if this is a wrapper then return the result of recursively calling
     * <code>isWrapperFor</code> on the wrapped object. If this does not implement the interface and is not a wrapper, return false. This method
     * should be implemented as a low-cost operation compared to <code>unwrap</code> so that callers can use this method to avoid expensive
     * <code>unwrap</code> calls that may fail. If this method returns true then calling <code>unwrap</code> with the same argument should succeed.
     *
     * @param iface a Class defining an interface.
     * @return true if this implements the interface or directly or indirectly wraps an object that does.
     * @throws SQLException if an error occurs while determining whether this is a wrapper for an object with the given interface.
     * @since 1.6
     */
    public boolean isWrapperFor(final Class<?> iface) throws SQLException {
        return iface.isInstance(this);
    }

    /**
     * returns the username for the connection.
     *
     * @return the username.
     */
    public String getUsername() {
        return protocol.getUsername();
    }

    /**
     * returns the password for the connection.
     *
     * @return the password.
     */
    public String getPassword() {
        return protocol.getPassword();
    }

    /**
     * returns the hostname for the connection.
     *
     * @return the hostname.
     */
    public String getHostname() {
        return protocol.getHost();
    }

    /**
     * returns the port for the connection.
     *
     * @return the port
     */
    public int getPort() {
        return protocol.getPort();
    }

    /**
     * returns the database.
     *
     * @return the database
     */
    public String getDatabase() {
        return protocol.getDatabase();
    }

    protected boolean getPinGlobalTxToPhysicalConnection() {
        return protocol.getPinGlobalTxToPhysicalConnection();
    }

    /**
     * If failover is not activated, will close connection when a connection error occur.
     */
    public void setHostFailed() {
        if (protocol.getProxy() == null) {
            protocol.setHostFailedWithoutProxy();
        }
    }

    /**
     * Are table case sensitive or not . Default Value: 0 (Unix), 1 (Windows), 2 (Mac OS X). If set to 0 (the default on Unix-based systems), table
     * names and aliases and database names are compared in a case-sensitive manner. If set to 1 (the default on Windows), names are stored in
     * lowercase and not compared in a case-sensitive manner. If set to 2 (the default on Mac OS X), names are stored as declared, but compared in
     * lowercase.
     *
     * @return int value.
     * @throws SQLException if a connection error occur
     */
    public int getLowercaseTableNames() throws SQLException {
        if (lowercaseTableNames == -1) {
            Statement st = createStatement();
            ResultSet rs = st.executeQuery("select @@lower_case_table_names");
            rs.next();
            lowercaseTableNames = rs.getInt(1);
        }
        return lowercaseTableNames;
    }

    /**
     * Abort connection.
     *
     * @param executor executor
     * @throws SQLException if security manager doesn't permit it.
     */
    public void abort(Executor executor) throws SQLException {
        if (this.isClosed()) {
            return;
        }
        SQLPermission sqlPermission = new SQLPermission("callAbort");
        SecurityManager securityManager = System.getSecurityManager();
        if (securityManager != null && sqlPermission != null) {
            securityManager.checkPermission(sqlPermission);
        }
        if (executor == null) {
            throw ExceptionMapper.getSqlException("Cannot abort the connection: null executor passed");
        }
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    close();
                    pooledConnection = null;
                } catch (SQLException sqle) {
                    throw new RuntimeException(sqle);
                }
            }
        });
    }

    /**
     * Get network timeout.
     *
     * @return timeout
     * @throws SQLException if database socket error occur
     */
    public int getNetworkTimeout() throws SQLException {
        try {
            return this.protocol.getTimeout();
        } catch (SocketException se) {
            throw ExceptionMapper.getSqlException("Cannot retrieve the network timeout", se);
        }
    }

    public String getSchema() throws SQLException {
        // We support only catalog
        return null;
    }

    public void setSchema(String arg0) throws SQLException {
        // We support only catalog, and JDBC indicate "If the driver does not support schemas, it will silently ignore this request."
    }


    /**
     * Change network timeout
     *
     * @param executor     executor (can be null)
     * @param milliseconds network timeout in milliseconds.
     * @throws SQLException if security manager doesn't permit it.
     */
    public void setNetworkTimeout(Executor executor, final int milliseconds) throws SQLException {
        if (this.isClosed()) {
            throw ExceptionMapper.getSqlException("Connection.setNetworkTimeout cannot be called on a closed connection");
        }
        if (milliseconds < 0) {
            throw ExceptionMapper.getSqlException("Connection.setNetworkTimeout cannot be called with a negative timeout");
        }
        SQLPermission sqlPermission = new SQLPermission("setNetworkTimeout");
        SecurityManager securityManager = System.getSecurityManager();
        if (securityManager != null && sqlPermission != null) {
            securityManager.checkPermission(sqlPermission);
        }
        if (executor == null) {
            throw ExceptionMapper.getSqlException("Cannot set the connection timeout: null executor passed");
        }
        try {
            protocol.setTimeout(milliseconds);
        } catch (SocketException se) {
            throw ExceptionMapper.getSqlException("Cannot set the network timeout", se);
        }
    }

    protected String getServerTimezone() {
        return options.serverTimezone;
    }

    protected Options getOptions() {
        return options;
    }

    public ClientPrepareStatementCache getClientPrepareStatementCache() {
        return clientPrepareStatementCache;
    }

    public long getServerThreadId() {
        return (protocol != null) ? protocol.getServerThreadId() : -1;
    }
}

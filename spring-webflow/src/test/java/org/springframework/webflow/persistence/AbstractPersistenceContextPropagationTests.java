/*
 * Copyright 2004-2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.webflow.persistence;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import junit.framework.TestCase;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.webflow.engine.EndState;
import org.springframework.webflow.execution.FlowExecutionListener;
import org.springframework.webflow.execution.FlowSession;
import org.springframework.webflow.test.MockFlowSession;
import org.springframework.webflow.test.MockRequestContext;

public abstract class AbstractPersistenceContextPropagationTests extends TestCase {

	private MockRequestContext requestContext;

	private JdbcTemplate jdbcTemplate;

	public JdbcTemplate getJdbcTemplate() {
		return jdbcTemplate;
	}

	protected final void setUp() throws Exception {
		requestContext = new MockRequestContext();
		DataSource dataSource = getDataSource();
		jdbcTemplate = new JdbcTemplate(dataSource);
		populateDataBase(dataSource);
		setUpResources(dataSource);
	}

	public void testSessionStarting_NoPc_ParentPc() {
		MockFlowSession parentSession = newFlowSession(true, null);
		MockFlowSession childSession = newFlowSession(false, parentSession);

		getListener().sessionStarting(new MockRequestContext(), parentSession, null);
		assertSessionBound();
		assertSessionInScope(parentSession);

		getListener().sessionStarting(new MockRequestContext(), childSession, null);
		assertSessionNotBound();
		assertSessionNotInScope(childSession);
	}

	public void testSessionStarting_Pc_ParentPc() {
		MockFlowSession parentSession = newFlowSession(true, null);
		MockFlowSession childSession = newFlowSession(true, parentSession);

		getListener().sessionStarting(new MockRequestContext(), parentSession, null);
		assertSessionBound();
		assertSessionInScope(parentSession);

		getListener().sessionStarting(new MockRequestContext(), childSession, null);
		assertSessionBound();
		assertSessionInScope(childSession);
		assertSame("Parent PersistenceContext should be re-used", parentSession.getScope().get("persistenceContext"),
				childSession.getScope().get("persistenceContext"));
	}

	public void testSessionEnd_Pc_NoParentPc() {
		MockFlowSession parentSession = newFlowSession(false, null);
		MockFlowSession childSession = newFlowSession(true, parentSession);

		getListener().sessionStarting(requestContext, parentSession, null);
		getListener().sessionStarting(requestContext, childSession, null);

		assertCommitState(true, false);

		requestContext.setActiveSession(childSession);

		// Session ending commits, unbinds/closes PersistenceContext
		getListener().sessionEnding(requestContext, childSession, "success", null);
		assertSessionNotBound();

		// sessionEnded has no effect
		getListener().sessionEnded(requestContext, childSession, "success", null);
		assertSessionNotBound();
		assertCommitState(false, true);
	}

	public void testSessionEnd_Pc_ParentPc() {
		MockFlowSession parentSession = newFlowSession(true, null);
		MockFlowSession childSession = newFlowSession(true, parentSession);

		getListener().sessionStarting(requestContext, parentSession, null);
		getListener().sessionStarting(requestContext, childSession, null);

		assertCommitState(true, false);

		requestContext.setActiveSession(childSession);

		// sessionEnding is a no-op
		getListener().sessionEnding(requestContext, childSession, "success", null);
		assertSessionBound();
		assertCommitState(true, false);

		// sessionEnded binds Parent PersistenceContext
		getListener().sessionEnded(requestContext, childSession, "success", null);
		assertSessionBound();
	}

	private MockFlowSession newFlowSession(boolean persistenceContext, FlowSession parent) {
		MockFlowSession flowSession = new MockFlowSession();
		flowSession.setParent(parent);
		if (persistenceContext) {
			flowSession.getDefinition().getAttributes().put("persistenceContext", "true");
		}
		EndState endState = new EndState(flowSession.getDefinitionInternal(), "success");
		endState.getAttributes().put("commit", Boolean.TRUE);
		flowSession.setState(endState);
		return flowSession;
	}

	private DataSource getDataSource() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource();
		dataSource.setDriverClassName("org.hsqldb.jdbcDriver");
		dataSource.setUrl("jdbc:hsqldb:mem:hspcl");
		dataSource.setUsername("sa");
		dataSource.setPassword("");
		return dataSource;
	}

	private void populateDataBase(DataSource dataSource) {
		Connection connection = null;
		try {
			connection = dataSource.getConnection();
			connection.createStatement().execute("drop table T_ADDRESS if exists;");
			connection.createStatement().execute("drop table T_BEAN if exists;");
			connection.createStatement().execute(
					"create table T_BEAN (ID integer primary key, NAME varchar(50) not null);");
			connection.createStatement().execute(
					"create table T_ADDRESS (ID integer primary key, BEAN_ID integer, VALUE varchar(50) not null);");
			connection
					.createStatement()
					.execute(
							"alter table T_ADDRESS add constraint FK_BEAN_ADDRESS foreign key (BEAN_ID) references T_BEAN(ID) on delete cascade");
			connection.createStatement().execute("insert into T_BEAN (ID, NAME) values (0, 'Ben Hale');");
			connection.createStatement().execute(
					"insert into T_ADDRESS (ID, BEAN_ID, VALUE) values (0, 0, 'Melbourne')");
		} catch (SQLException e) {
			throw new RuntimeException("SQL exception occurred acquiring connection", e);
		} finally {
			if (connection != null) {
				try {
					connection.close();
				} catch (SQLException e) {
				}
			}
		}
	}

	/* methods for subclasses */

	protected abstract void setUpResources(DataSource dataSource) throws Exception;

	protected abstract FlowExecutionListener getListener();

	protected abstract void assertSessionBound();

	protected abstract void assertSessionNotBound();

	protected abstract void assertCommitState(boolean b, boolean c);

	/* private helper methods */

	private void assertSessionInScope(FlowSession session) {
		assertTrue(session.getScope().contains("persistenceContext"));
	}

	private void assertSessionNotInScope(FlowSession session) {
		assertFalse(session.getScope().contains("persistenceContext"));
	}

}

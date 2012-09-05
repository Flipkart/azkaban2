package azkaban.webapp.servlet;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import azkaban.executor.ExecutableFlow;
import azkaban.executor.ExecutableFlow.ExecutableNode;
import azkaban.executor.ExecutorManager;
import azkaban.executor.ExecutableFlow.Status;
import azkaban.executor.ExecutorManagerException;
import azkaban.flow.Flow;
import azkaban.project.Project;
import azkaban.project.ProjectManager;
import azkaban.project.ProjectManagerException;
import azkaban.user.Permission;
import azkaban.user.User;
import azkaban.user.Permission.Type;
import azkaban.webapp.session.Session;

public class FlowExecutorServlet extends LoginAbstractAzkabanServlet {
	private static final long serialVersionUID = 1L;
	private ProjectManager projectManager;
	private ExecutorManager executorManager;

	@Override
	public void init(ServletConfig config) throws ServletException {
		super.init(config);
		projectManager = this.getApplication().getProjectManager();
		executorManager = this.getApplication().getExecutorManager();
	}

	@Override
	protected void handleGet(HttpServletRequest req, HttpServletResponse resp, Session session) throws ServletException, IOException {
		if (hasParam(req, "ajax")) {
			handleAJAXAction(req, resp, session);
		}
		else if (hasParam(req, "execid")) {
			handleExecutionFlowPage(req, resp, session);
		}
	}
	
	private void handleExecutionFlowPage(HttpServletRequest req, HttpServletResponse resp, Session session) throws ServletException, IOException {
		Page page = newPage(req, resp, session, "azkaban/webapp/servlet/velocity/executingflowpage.vm");
		User user = session.getUser();
		String execId = getParam(req, "execid");
		page.add("execid", execId);

		ExecutableFlow flow = null;
		try {
			flow = executorManager.getExecutableFlow(execId);
			if (flow == null) {
				page.add("errorMsg", "Error loading executing flow " + execId + " not found.");
				page.render();
				return;
			}
		} catch (ExecutorManagerException e) {
			page.add("errorMsg", "Error loading executing flow: " + e.getMessage());
			page.render();
			return;
		}
		
		String projectId = flow.getProjectId();
		Project project = getProjectPageByPermission(page, flow.getProjectId(), user, Type.READ);
		if (project == null) {
			page.render();
			return;
		}
		
		page.add("projectName", projectId);
		page.add("flowid", flow.getFlowId());
		
		page.render();
	}
	
	protected Project getProjectPageByPermission(Page page, String projectId, User user, Permission.Type type) {
		Project project = projectManager.getProject(projectId);
		
		if (project == null) {
			page.add("errorMsg", "Project " + project + " not found.");
		}
		else if (!project.hasPermission(user, type)) {
			page.add("errorMsg", "User " + user.getUserId() + " doesn't have " + type.name() + " permissions on " + projectId);
		}
		else {
			return project;
		}
		
		return null;
	}

	protected Project getProjectAjaxByPermission(Map<String, Object> ret, String projectId, User user, Permission.Type type) {
		Project project = projectManager.getProject(projectId);
		
		if (project == null) {
			ret.put("error", "Project " + project + " not found.");
		}
		else if (!project.hasPermission(user, type)) {
			ret.put("error", "User " + user.getUserId() + " doesn't have " + type.name() + " permissions on " + projectId);
		}
		else {
			return project;
		}
		
		return null;
	}

	@Override
	protected void handlePost(HttpServletRequest req, HttpServletResponse resp, Session session) throws ServletException, IOException {
		if (hasParam(req, "ajax")) {
			handleAJAXAction(req, resp, session);
		}
	}

	private void handleAJAXAction(HttpServletRequest req, HttpServletResponse resp, Session session) throws ServletException, IOException {
		HashMap<String, Object> ret = new HashMap<String, Object>();
		String ajaxName = getParam(req, "ajax");
		
		if (hasParam(req, "execid")) {
			if (ajaxName.equals("fetchexecflow")) {
				ajaxFetchExecutableFlow(req, resp, ret, session.getUser());
			}
			else if (ajaxName.equals("fetchexecflowupdate")) {
				ajaxFetchExecutableFlowUpdate(req, resp, ret, session.getUser());
			}
		}
		else {
			String projectName = getParam(req, "project");
			
			ret.put("project", projectName);
			if (ajaxName.equals("executeFlow")) {
				ajaxExecuteFlow(req, resp, ret, session.getUser());
			}
		}
		this.writeJSON(resp, ret);
	}

	private void ajaxFetchExecutableFlowUpdate(HttpServletRequest req, HttpServletResponse resp, HashMap<String, Object> ret, User user) throws ServletException{
		String execid = getParam(req, "execid");
		Long lastUpdateTime = Long.parseLong(getParam(req, "lastUpdateTime"));
		
		System.out.println("Fetching " + execid);
		
		ExecutableFlow exFlow = null;
		try {
			exFlow = executorManager.getExecutableFlow(execid);
		} catch (ExecutorManagerException e) {
			ret.put("error", "Error fetching execution '" + execid + "': " + e.getMessage());
		}
		if (exFlow == null) {
			ret.put("error", "Cannot find execution '" + execid + "'");
			return;
		}
		
		Project project = getProjectAjaxByPermission(ret, exFlow.getProjectId(), user, Type.READ);
		if (project == null) {
			return;
		}

		// Just update the nodes and flow states
		ArrayList<Map<String, Object>> nodeList = new ArrayList<Map<String, Object>>();
		for (ExecutableNode node : exFlow.getExecutableNodes()) {
			if (node.getStartTime() < lastUpdateTime && node.getEndTime() < lastUpdateTime) {
				continue;
			}
			
			HashMap<String, Object> nodeObj = new HashMap<String,Object>();
			nodeObj.put("id", node.getId());
			nodeObj.put("status", node.getStatus());
			nodeObj.put("startTime", node.getStartTime());
			nodeObj.put("endTime", node.getEndTime());
			
			nodeList.add(nodeObj);
		}

		ret.put("nodes", nodeList);
		ret.put("status", exFlow.getStatus().toString());
		ret.put("startTime", exFlow.getStartTime());
		ret.put("endTime", exFlow.getEndTime());
		ret.put("submitTime", exFlow.getSubmitTime());
	}
	
	private void ajaxFetchExecutableFlow(HttpServletRequest req, HttpServletResponse resp, HashMap<String, Object> ret, User user) throws ServletException {
		String execid = getParam(req, "execid");
		System.out.println("Fetching " + execid);
		ExecutableFlow exFlow = null;
		try {
			exFlow = executorManager.getExecutableFlow(execid);
		} catch (ExecutorManagerException e) {
			ret.put("error", "Error fetching execution '" + execid + "': " + e.getMessage());
		}
		if (exFlow == null) {
			ret.put("error", "Cannot find execution '" + execid + "'");
			return;
		}
		
		Project project = getProjectAjaxByPermission(ret, exFlow.getProjectId(), user, Type.READ);
		if (project == null) {
			return;
		}
		
		ArrayList<Map<String, Object>> nodeList = new ArrayList<Map<String, Object>>();
		ArrayList<Map<String, Object>> edgeList = new ArrayList<Map<String,Object>>();
		for (ExecutableNode node : exFlow.getExecutableNodes()) {
			HashMap<String, Object> nodeObj = new HashMap<String,Object>();
			nodeObj.put("id", node.getId());
			nodeObj.put("level", node.getLevel());
			nodeObj.put("status", node.getStatus());
			nodeObj.put("startTime", node.getStartTime());
			nodeObj.put("endTime", node.getEndTime());
			
			nodeList.add(nodeObj);
			
			// Add edges
			for (String out: node.getOutNodes()) {
				HashMap<String, Object> edgeObj = new HashMap<String,Object>();
				edgeObj.put("from", node.getId());
				edgeObj.put("target", out);
				edgeList.add(edgeObj);
			}
		}

		ret.put("nodes", nodeList);
		ret.put("edges", edgeList);
		ret.put("status", exFlow.getStatus().toString());
		ret.put("startTime", exFlow.getStartTime());
		ret.put("endTime", exFlow.getEndTime());
		ret.put("submitTime", exFlow.getSubmitTime());
		ret.put("submitUser", exFlow.getSubmitUser());
	}
	
	private void ajaxExecuteFlow(HttpServletRequest req, HttpServletResponse resp, HashMap<String, Object> ret, User user) throws ServletException {
		String projectId = getParam(req, "project");
		String flowId = getParam(req, "flow");
		
		ret.put("flow", flowId);
		
		Project project = getProjectAjaxByPermission(ret, projectId, user, Type.EXECUTE);
		if (project == null) {
			return;
		}

		Flow flow = project.getFlow(flowId);
		if (flow == null) {
			ret.put("error", "Flow " + flowId + " cannot be found in project " + project);
			return;
		}
		
		// Create ExecutableFlow
		ExecutableFlow exflow = executorManager.createExecutableFlow(flow);
		exflow.setSubmitUser(user.getUserId());
		Map<String, String> paramGroup = this.getParamGroup(req, "disabled");
		for (Map.Entry<String, String> entry: paramGroup.entrySet()) {
			boolean nodeDisabled = Boolean.parseBoolean(entry.getValue());
			exflow.setStatus(entry.getKey(), nodeDisabled ? Status.DISABLED : Status.READY);
		}
		
		// Create directory
		try {
			executorManager.setupExecutableFlow(exflow);
		} catch (ExecutorManagerException e) {
			try {
				executorManager.cleanupAll(exflow);
			} catch (ExecutorManagerException e1) {
				e1.printStackTrace();
			}
			ret.put("error", e.getMessage());
			return;
		}

		// Copy files to the source.
		File executionDir = new File(exflow.getExecutionPath());
		try {
			projectManager.copyProjectSourceFilesToDirectory(project, executionDir);
		} catch (ProjectManagerException e) {
			try {
				executorManager.cleanupAll(exflow);
			} catch (ExecutorManagerException e1) {
				e1.printStackTrace();
			}
			ret.put("error", e.getMessage());
			return;
		}
		

		try {
			executorManager.executeFlow(exflow);
		} catch (ExecutorManagerException e) {
			try {
				executorManager.cleanupAll(exflow);
			} catch (ExecutorManagerException e1) {
				e1.printStackTrace();
			}
			
			ret.put("error", e.getMessage());
			return;
		}

		String execId = exflow.getExecutionId();

		ret.put("execid", execId);
	}
}

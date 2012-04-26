package org.opennms.features.vaadin.topology;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.xml.bind.JAXB;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlID;
import javax.xml.bind.annotation.XmlIDREF;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;

import com.vaadin.data.Item;
import com.vaadin.data.util.BeanContainer;
import com.vaadin.data.util.BeanItem;

public class SimpleGraphContainer implements GraphContainer {


	@XmlRootElement(name="vertex")
	private static class SimpleVertex {
		String m_id;
		int m_x;
		int m_y;

		List<SimpleEdge> m_edges = new ArrayList<SimpleEdge>();

		public SimpleVertex(String id, int x, int y) {
			m_id = id;
			m_x = x;
			m_y = y;
		}

		@XmlID
		public String getId() {
			return m_id;
		}

		public void setId(String id) {
			m_id = id;
		}

		public int getX() {
			return m_x;
		}

		public void setX(int x) {
			m_x = x;
		}

		public int getY() {
			return m_y;
		}

		public void setY(int y) {
			m_y = y;
		}
	
		@XmlTransient
		List<SimpleEdge> getEdges() {
			return m_edges;
		}
		
		void addEdge(SimpleEdge edge) {
			m_edges.add(edge);
		}
		
		void removeEdge(SimpleEdge edge) {
			m_edges.remove(edge);
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + ((m_id == null) ? 0 : m_id.hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			SimpleVertex other = (SimpleVertex) obj;
			if (m_id == null) {
				if (other.m_id != null)
					return false;
			} else if (!m_id.equals(other.m_id))
				return false;
			return true;
		}
		
		
	}
	

	@XmlRootElement(name="edge")
	private static class SimpleEdge {
		String m_id;
		SimpleVertex m_source;
		SimpleVertex m_target;
		
		
		public SimpleEdge(String id, SimpleVertex source, SimpleVertex target) {
			m_id = id;
			m_source = source;
			m_target = target;
			
			m_source.addEdge(this);
			m_target.addEdge(this);
		}

		@XmlID
		public String getId() {
			return m_id;
		}

		public void setId(String id) {
			m_id = id;
		}
		
		@XmlIDREF
		public SimpleVertex getSource() {
			return m_source;
		}

		public void setSource(SimpleVertex source) {
			m_source = source;
		}

		@XmlIDREF
		public SimpleVertex getTarget() {
			return m_target;
		}

		public void setTarget(SimpleVertex target) {
			m_target = target;
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + ((m_id == null) ? 0 : m_id.hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			SimpleEdge other = (SimpleEdge) obj;
			if (m_id == null) {
				if (other.m_id != null)
					return false;
			} else if (!m_id.equals(other.m_id))
				return false;
			return true;
		}
		
	}
	
	private BeanContainer<String, SimpleVertex> m_vertexContainer = new BeanContainer<String, SimpleVertex>(SimpleVertex.class);
	private BeanContainer<String, SimpleEdge> m_edgeContainer = new BeanContainer<String, SimpleEdge>(SimpleEdge.class);
	
	public SimpleGraphContainer() {
		m_vertexContainer = new BeanContainer<String, SimpleVertex>(SimpleVertex.class);
		m_vertexContainer.setBeanIdProperty("id");
		m_edgeContainer = new BeanContainer<String, SimpleEdge>(SimpleEdge.class);
		m_edgeContainer.setBeanIdProperty("id");
	}

	@SuppressWarnings("unchecked")
	public BeanContainer<?, ?> getVertexContainer() {
		return m_vertexContainer;
	}

	@SuppressWarnings("unchecked")
	public BeanContainer<?, ?> getEdgeContainer() {
		return m_edgeContainer;
	}

	public Collection<?> getVertexIds() {
		return m_vertexContainer.getItemIds();
	}

	public Collection<?> getEdgeIds() {
		return m_edgeContainer.getItemIds();
	}

	public Item getVertexItem(Object vertexId) {
		return m_vertexContainer.getItem(vertexId);
	}

	public Item getEdgeItem(Object edgeId) {
		return m_edgeContainer.getItem(edgeId);
	}

	public Collection<? extends Item> getEdgeEndPoints(Object edgeId) {
		
		SimpleEdge edge = getRequiredEdge(edgeId);

		List<BeanItem<SimpleVertex>> endPoints = new ArrayList<BeanItem<SimpleVertex>>(2);

		String sourceId = edge.getSource().getId();
		String targetId = edge.getTarget().getId();
		
		BeanItem<SimpleVertex> source = m_vertexContainer.getItem(sourceId);
		BeanItem<SimpleVertex> target = m_vertexContainer.getItem(targetId);
		
		if (source == null || target == null) {
			throw new IllegalStateException("edge "+edgeId+" references vertices that do not exist!");
		}
		
		endPoints.add(source);
		endPoints.add(target);
		
		return endPoints;
	}

	public Collection<? extends Item> getEdgesForVertex(Object vertexId) {
		SimpleVertex vertex = getRequiredVertex(vertexId);
		
		List<BeanItem<SimpleEdge>> edgeItems = new ArrayList<BeanItem<SimpleEdge>>(vertex.getEdges().size());
		
		for(SimpleEdge e : vertex.getEdges()) {
			String edgeId = e.getId();
			BeanItem<SimpleEdge> edgeItem = m_edgeContainer.getItem(edgeId);
			
			if (edgeItem == null) {
				throw new IllegalStateException("vertex " + vertexId + " has an edge that does not exist!");
			}
			
			edgeItems.add(edgeItem);
		}
		
		return edgeItems;

	}
	
	public void addVertex(String id, int x, int y) {
		if (m_vertexContainer.containsId(id)) {
			throw new IllegalArgumentException("A vertex with id " + id + " already exists!");
		}
		SimpleVertex vertex = new SimpleVertex(id, x, y);
		m_vertexContainer.addBean(vertex);
	}
	
	public void connectVertices(String id, String sourceVertextId, String targetVertextId) {
		SimpleVertex source = getRequiredVertex(sourceVertextId);
		SimpleVertex target = getRequiredVertex(targetVertextId);
		
		SimpleEdge edge = new SimpleEdge(id, source, target);
		
		m_edgeContainer.addBean(edge);
		
	}
	
	public void removeVertex(String vertexId) {
		SimpleVertex vertex = getVertex(vertexId, false);
		if (vertex == null) return;
		
		m_vertexContainer.removeItem(vertexId);
		
		for(SimpleEdge e : vertex.getEdges()) {
			m_edgeContainer.removeItem(e.getId());
		}
				
		
	}

	private SimpleVertex getRequiredVertex(Object vertexId) {
		return getVertex(vertexId, true);
	}

	private SimpleVertex getVertex(Object vertexId, boolean required) {
		BeanItem<SimpleVertex> item = m_vertexContainer.getItem(vertexId);
		if (required && item == null) {
			throw new IllegalArgumentException("required vertex " + vertexId + " not found.");
		}
		
		return item == null ? null : item.getBean();
	}

	private SimpleEdge getRequiredEdge(Object edgeId) {
		return getEdge(edgeId, true);
	}

	private SimpleEdge getEdge(Object edgeId, boolean required) {
		BeanItem<SimpleEdge> item = m_edgeContainer.getItem(edgeId);
		if (required && item == null) {
			throw new IllegalArgumentException("required edge " + edgeId + " not found.");
		}
		
		return item == null ? null : item.getBean();
	}
	

	@XmlRootElement(name="graph")
	@XmlAccessorType(XmlAccessType.FIELD)
	private static class SimpleGraph {
		
		@XmlElement(name="vertex")
		List<SimpleVertex> m_vertices = new ArrayList<SimpleVertex>();
		
		@XmlElement(name="edge")
		List<SimpleEdge> m_edges = new ArrayList<SimpleEdge>();
		
		public SimpleGraph() {}

		public SimpleGraph(List<SimpleVertex> vertices, List<SimpleEdge> edges) {
			m_vertices = vertices;
			m_edges = edges;
		}

	}
	
	public void save() {
		List<SimpleVertex> vertices = getBeans(m_vertexContainer);
		List<SimpleEdge> edges = getBeans(m_edgeContainer);

		SimpleGraph graph = new SimpleGraph(vertices, edges);
		
		JAXB.marshal(graph, new File("graph.xml"));
		
	}
	
	public void load() {
		SimpleGraph graph = JAXB.unmarshal(new File("graph.xml"), SimpleGraph.class);
		
		m_vertexContainer.removeAllItems();
		m_vertexContainer.addAll(graph.m_vertices);
		
		m_edgeContainer.removeAllItems();
		m_edgeContainer.addAll(graph.m_edges);
	}
	
	private <T> List<T> getBeans(BeanContainer<?, T> container) {
		Collection<?> itemIds = container.getItemIds();
		List<T> beans = new ArrayList<T>(itemIds.size());
		
		for(Object itemId : itemIds) {
			beans.add(container.getItem(itemId).getBean());
		}
		
		return beans;
	}
}
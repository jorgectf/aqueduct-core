package com.tesco.aqueduct.registry;

import com.fasterxml.jackson.databind.JavaType;
import com.tesco.aqueduct.pipe.api.JsonHelper;
import org.postgresql.util.PSQLException;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class PostgreSQLNodeRegistry implements NodeRegistry {

    private final URL cloudUrl;
    private final Duration offlineDelta;
    private final DataSource dataSource;
    private static final int NUMBER_OF_CHILDREN = 2;

    private static final RegistryLogger LOG = new RegistryLogger(LoggerFactory.getLogger(PostgreSQLNodeRegistry.class));

    public PostgreSQLNodeRegistry(DataSource dataSource, URL cloudUrl, Duration offlineDelta) {
        this.cloudUrl = cloudUrl;
        this.offlineDelta = offlineDelta;
        this.dataSource = dataSource;
    }

    @Override
    public List<URL> register(Node node) {
        while(true) {
            try (Connection connection = dataSource.getConnection()) {
                NodeGroup groupNodes = getNodeGroup(connection, node.getGroup());
                ZonedDateTime now = ZonedDateTime.now();

                if (groupNodes.nodes.isEmpty()) {
                    return addNodeToNewGroup(connection, node, now);
                } else {
                    Optional<Node> existingNode = groupNodes
                        .nodes
                        .stream()
                        .filter(n -> n.getId().equals(node.getId()))
                        .findAny();

                    if (existingNode.isPresent()) {
                        return updateExistingNode(connection, groupNodes.version, existingNode.get(), node, groupNodes.nodes);
                    } else {
                        return addNodeToExistingGroup(connection, groupNodes.version, groupNodes.nodes, node, now);
                    }
                }
            } catch (SQLException | IOException exception) {
                LOG.error("Postgresql node registry", "register node", exception);
                throw new RuntimeException(exception);
            } catch (VersionChangedException exception) {
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    @Override
    public StateSummary getSummary(long offset, String status, List<String> groups) {
        try (Connection connection = dataSource.getConnection()) {

            List<Node> followers;

            if (groups == null || groups.isEmpty()) {
                followers = getAllNodes(connection);
            } else {
                followers = getNodesFilteredByGroup(connection, groups)
                    .stream()
                    .flatMap(
                        group -> group.nodes.stream()
                    ).collect(Collectors.toList());
            }

            followers = followers
                .stream()
                .map(this::changeStatusIfOffline)
                .collect(Collectors.toList());

            Node node = Node.builder()
                .localUrl(cloudUrl)
                .offset(offset)
                .status(status)
                .following(Collections.emptyList())
                .lastSeen(ZonedDateTime.now())
                .build();

            return new StateSummary(node, followers);
        } catch (SQLException exception) {
            LOG.error("Postgresql node registry", "get summary", exception);
            throw new RuntimeException(exception);
        }
    }

    private List<URL> updateExistingNode(Connection connection, int version, Node existingValue, Node newValues, List<Node> groupNodes) throws SQLException, IOException {
        for (int i = 0; i < groupNodes.size(); i++) {
            if (groupNodes.get(i).getId().equals(newValues.getId())) {
                Node updatedNode = newValues.toBuilder()
                    .requestedToFollow(existingValue.getRequestedToFollow())
                    .lastSeen(ZonedDateTime.now())
                    .build();

                groupNodes.set(i, updatedNode);
                persistGroup(connection, version, groupNodes);

                return updatedNode.getRequestedToFollow();
            }
        }

        throw new IllegalStateException("The node was not found " + newValues.getId());
    }

    private List<URL> addNodeToNewGroup(Connection connection, Node node, ZonedDateTime now) throws SQLException, IOException {
        List<Node> groupNodes = new ArrayList<>();
        List<URL> followUrls = Collections.singletonList(cloudUrl);

        Node updatedNode =
            node.toBuilder()
                .requestedToFollow(followUrls)
                .lastSeen(now)
                .build();

        groupNodes.add(updatedNode);
        boolean inserted = insertNewGroup(connection, groupNodes);

        if (!inserted){
            throw new VersionChangedException();
        }

        return followUrls;
    }

    private List<URL> addNodeToExistingGroup(Connection connection, int version, List<Node> groupNodes, Node node, ZonedDateTime now) throws SQLException, IOException {
        List<URL> allUrls = groupNodes.stream().map(Node::getLocalUrl).collect(Collectors.toList());

        int nodeIndex = allUrls.size();
        List<URL> followUrls = new ArrayList<>();

        while (nodeIndex != 0) {
            nodeIndex = ((nodeIndex+1)/NUMBER_OF_CHILDREN) - 1;
            followUrls.add(allUrls.get(nodeIndex));
        }

        followUrls.add(cloudUrl);

        groupNodes.add(
            node.toBuilder()
                .requestedToFollow(followUrls)
                .lastSeen(now)
                .build()
        );

        persistGroup(connection, version, groupNodes);

        return followUrls;
    }

    private Node changeStatusIfOffline(Node node) {
        ZonedDateTime threshold = ZonedDateTime.now().minus(offlineDelta);

        if (node.getLastSeen().compareTo(threshold) < 0) {
            return node.toBuilder().status("offline").build();
        }
        return node;
    }

    private List<NodeGroup> getNodesFilteredByGroup(Connection connection, List<String> groups) throws SQLException {
        List<NodeGroup> list = new ArrayList<>();
        for (String group : groups) {
            NodeGroup nodeGroup = getNodeGroup(connection, group);
            list.add(nodeGroup);
        }
        return list;
    }

    // Methods that interact with the database - possibly need to split these into another class

    private NodeGroup getNodeGroup(Connection connection, String group) throws SQLException {
        List<Node> nodes;
        int version;
        try (PreparedStatement statement = connection.prepareStatement(getNodeGroupQuery())) {

            statement.setString(1, group);

            nodes = new ArrayList<>();
            version = 0;

            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String entry = rs.getString("entry");
                    version = rs.getInt("version");

                    nodes.addAll(readGroupEntry(entry));
                }
            } catch (IOException e) {
                e.printStackTrace();
                throw new UncheckedIOException(e);
            }
        }

        return new NodeGroup(nodes, version);
    }

    private List<Node> readGroupEntry(String entry) throws IOException {
        JavaType type = JsonHelper.MAPPER.getTypeFactory().constructCollectionType(List.class, Node.class);
        return JsonHelper.MAPPER.readValue(entry, type);
    }

    private void persistGroup(Connection connection, int version, List<Node> groupNodes) throws SQLException, IOException {
        try (PreparedStatement statement = connection.prepareStatement(getPersistGroupQuery())) {

            String jsonNodes = JsonHelper.toJson(groupNodes);

            statement.setString(1, jsonNodes);
            statement.setString(2, groupNodes.get(0).getGroup());
            statement.setInt(3, version);

            if (statement.executeUpdate() == 0) {
                throw new VersionChangedException();
            }
        }
    }

    private boolean insertNewGroup(Connection connection, List<Node> groupNodes) throws IOException, SQLException {
        try (PreparedStatement statement = connection.prepareStatement(getInsertGroupQuery())) {

            String jsonNodes = JsonHelper.toJson(groupNodes);

            statement.setString(1, groupNodes.get(0).getGroup());
            statement.setString(2, jsonNodes);

            return statement.executeUpdate() != 0;
        }
    }

    private List<Node> getAllNodes(Connection connection) throws SQLException {
        List<Node> nodes;
        try (PreparedStatement statement = connection.prepareStatement(getAllNodesQuery())) {

            nodes = new ArrayList<>();

            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String entry = rs.getString("entry");
                    nodes.addAll(readGroupEntry(entry));
                }
            } catch (IOException e) {
                e.printStackTrace();
                throw new UncheckedIOException(e);
            }
        }

        return nodes;
    }

    private static String getNodeGroupQuery() {
        return "SELECT entry, version FROM registry where group_id = ? ;";
    }

    private static String getPersistGroupQuery() {
        return "UPDATE registry SET " +
                    "entry = ?::JSON , " +
                    "version = registry.version + 1 " +
                "WHERE " +
                    "registry.group_id = ? " +
                "AND " +
                    "registry.version = ? " +
                ";";
    }

    private String getInsertGroupQuery() {
        return "INSERT INTO registry (group_id, entry, version)" +
                "VALUES (" +
                "?, " +
                "?::JSON , " +
                "0 " +
                ")" +
                "ON CONFLICT DO NOTHING ;";
    }

    private static String getAllNodesQuery() {
        return "SELECT entry FROM registry ;";
    }
}

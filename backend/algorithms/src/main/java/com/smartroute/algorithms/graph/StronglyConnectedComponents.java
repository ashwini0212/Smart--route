package com.smartroute.algorithms.graph;

import java.util.ArrayList;
import java.util.List;

/**
 * Kosaraju's algorithm for strongly connected components (SCCs).
 *
 * <p>Problem it solves: with one-way streets, "connected" is not enough. A node can be reachable
 * but have no way back (a dead end), or be impossible to reach. Inside one SCC every node can reach
 * every other node. Routing only inside the largest SCC guarantees that any pickup/drop pair has a
 * route in both directions, so "unreachable" becomes a data-quality report at load time instead of a
 * surprise at request time.
 *
 * <p>Algorithm (two DFS passes):
 * <ol>
 *   <li>DFS the graph and record nodes in order of finishing time.</li>
 *   <li>DFS the <em>reversed</em> graph, starting from nodes in decreasing finishing time.
 *       Each tree found in this pass is exactly one SCC.</li>
 * </ol>
 * Time O(V + E) (two DFS passes plus building the reversed graph). Space O(V + E) for the reversed graph.
 *
 * <p>Alternative: Tarjan's algorithm finds SCCs in one pass with low-link values, without the
 * reversed graph. Kosaraju is chosen because it is easier to explain and verify; both are O(V + E).
 */
public final class StronglyConnectedComponents {

    private final int[] componentOf;
    private final int[] componentSizes;
    private final int largestComponent;

    private StronglyConnectedComponents(int[] componentOf, int[] componentSizes) {
        this.componentOf = componentOf;
        this.componentSizes = componentSizes;
        int best = -1;
        for (int c = 0; c < componentSizes.length; c++) {
            if (best == -1 || componentSizes[c] > componentSizes[best]) {
                best = c;
            }
        }
        this.largestComponent = best;
    }

    public static StronglyConnectedComponents of(Graph graph) {
        int n = graph.nodeCount();
        int[] finishOrder = finishOrder(graph);

        Graph reversed = graph.reversed();
        int[] componentOf = new int[n];
        java.util.Arrays.fill(componentOf, -1);
        List<Integer> sizes = new ArrayList<>();
        int[] stack = new int[n];

        for (int i = n - 1; i >= 0; i--) {
            int start = finishOrder[i];
            if (componentOf[start] != -1) {
                continue;
            }
            int component = sizes.size();
            int size = 0;
            int top = 0;
            componentOf[start] = component;
            stack[top++] = start;
            while (top > 0) {
                int u = stack[--top];
                size++;
                for (Edge edge : reversed.outgoing(u)) {
                    int v = edge.to();
                    if (componentOf[v] == -1) {
                        componentOf[v] = component;
                        stack[top++] = v;
                    }
                }
            }
            sizes.add(size);
        }
        return new StronglyConnectedComponents(componentOf, sizes.stream().mapToInt(Integer::intValue).toArray());
    }

    /**
     * Iterative DFS that records each node when all of its descendants are finished (post-order).
     * Uses a stack of (node, next-edge-index) so we can resume a node's edge loop after a child returns.
     */
    private static int[] finishOrder(Graph graph) {
        int n = graph.nodeCount();
        boolean[] visited = new boolean[n];
        int[] order = new int[n];
        int orderSize = 0;
        int[] nodeStack = new int[n];
        int[] edgeIndexStack = new int[n];

        for (int root = 0; root < n; root++) {
            if (visited[root]) {
                continue;
            }
            int top = 0;
            visited[root] = true;
            nodeStack[top] = root;
            edgeIndexStack[top] = 0;
            top++;
            while (top > 0) {
                int u = nodeStack[top - 1];
                List<Edge> edges = graph.outgoing(u);
                int i = edgeIndexStack[top - 1];
                if (i < edges.size()) {
                    edgeIndexStack[top - 1] = i + 1;
                    int v = edges.get(i).to();
                    if (!visited[v]) {
                        visited[v] = true;
                        nodeStack[top] = v;
                        edgeIndexStack[top] = 0;
                        top++;
                    }
                } else {
                    order[orderSize++] = u; // all children done: u finishes now
                    top--;
                }
            }
        }
        return order;
    }

    public int componentCount() {
        return componentSizes.length;
    }

    public int componentOf(int nodeId) {
        return componentOf[nodeId];
    }

    public int sizeOf(int componentId) {
        return componentSizes[componentId];
    }

    /** Id of the biggest component, or -1 for an empty graph. Ties go to the lowest id. */
    public int largestComponent() {
        return largestComponent;
    }

    public boolean sameComponent(int a, int b) {
        return componentOf[a] == componentOf[b];
    }

    /** Node ids in the largest component, ascending. */
    public List<Integer> nodesInLargestComponent() {
        List<Integer> result = new ArrayList<>();
        for (int v = 0; v < componentOf.length; v++) {
            if (componentOf[v] == largestComponent) {
                result.add(v);
            }
        }
        return result;
    }
}

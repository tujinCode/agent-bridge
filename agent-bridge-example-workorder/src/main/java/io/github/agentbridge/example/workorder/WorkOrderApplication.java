package io.github.agentbridge.example.workorder;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Demo host application.
 *
 * <p>Run it and it stops talking to humans — stdin/stdout become an MCP channel.
 * Point Claude Desktop (or any MCP client) at the jar and ask about work order WO-2001.
 */
@SpringBootApplication
public class WorkOrderApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkOrderApplication.class, args);
    }
}

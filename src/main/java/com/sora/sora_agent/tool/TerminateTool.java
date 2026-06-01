package com.sora.sora_agent.tool;

import org.springframework.ai.tool.annotation.Tool;

/**
 * 参考manus实现的终止工具
 */
public class TerminateTool {

    @Tool(description = """  
            Terminate the interaction when the request is met OR if the assistant cannot proceed further with the task.  
            "When you have finished all the tasks, call this tool to end the work.  
            """)
    public String doTerminate() {
        return "任务结束";
    }
}


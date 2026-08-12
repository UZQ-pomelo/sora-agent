package com.sora.sora_agent.tool;

import cn.hutool.core.io.FileUtil;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Paragraph;
import com.sora.sora_agent.constant.FileConstant;
import com.sora.sora_agent.security.PathSafety;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.nio.file.Path;

/**
 * PDF生成工具
 *
 * <p>安全加固：文件名统一经 {@link PathSafety} 限制在沙箱目录内，防止路径穿越。</p>
 */
public class PDFGenerationTool {

    @Tool(description = "根据给定的内容生成PDF")
    public String generatePDF(
            @ToolParam(description = "生成的PDF的名字") String fileName,
            @ToolParam(description = "要用于生成PDF的内容") String content) {
        String fileDir = FileConstant.FILE_SAVE_DIR + "/pdf";
        try {
            Path file = PathSafety.resolve(fileDir, fileName);
            // 创建目录
            FileUtil.mkdir(fileDir);
            // 创建 PdfWriter 和 PdfDocument 对象
            try (PdfWriter writer = new PdfWriter(file.toFile());
                 PdfDocument pdf = new PdfDocument(writer);
                 Document document = new Document(pdf)) {
                // 自定义字体（需要人工下载字体文件到特定目录）
//                String fontPath = Paths.get("src/main/resources/static/fonts/simsun.ttf")
//                        .toAbsolutePath().toString();
//                PdfFont font = PdfFontFactory.createFont(fontPath,
//                        PdfFontFactory.EmbeddingStrategy.PREFER_EMBEDDED);
                // 使用内置中文字体
                PdfFont font = PdfFontFactory.createFont("STSongStd-Light", "UniGB-UCS2-H");
                document.setFont(font);
                // 创建段落
                Paragraph paragraph = new Paragraph(content);
                // 添加段落并关闭文档
                document.add(paragraph);
            }
            return "PDF成功生成至: " + file.toAbsolutePath();
        } catch (IOException e) {
            return "PDF生成错误: " + e.getMessage();
        }
    }
}


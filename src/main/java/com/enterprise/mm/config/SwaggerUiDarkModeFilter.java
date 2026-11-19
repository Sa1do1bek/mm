package com.enterprise.mm.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class SwaggerUiDarkModeFilter extends OncePerRequestFilter {
    private static final String THEME_ASSETS = """
        <style>
          html.swagger-dark-mode,
          body.swagger-dark-mode,
          body.swagger-dark-mode .swagger-ui {
            color-scheme: dark;
            background: #0f172a !important;
            color: #e5e7eb !important;
          }

          body.swagger-dark-mode .swagger-ui .topbar {
            background: #020617;
            border-bottom: 1px solid #334155;
          }

          body.swagger-dark-mode .swagger-ui .info .title,
          body.swagger-dark-mode .swagger-ui .info li,
          body.swagger-dark-mode .swagger-ui .info p,
          body.swagger-dark-mode .swagger-ui .info table,
          body.swagger-dark-mode .swagger-ui .scheme-container,
          body.swagger-dark-mode .swagger-ui .opblock-tag,
          body.swagger-dark-mode .swagger-ui .opblock .opblock-summary-description,
          body.swagger-dark-mode .swagger-ui .opblock .opblock-summary-path,
          body.swagger-dark-mode .swagger-ui .parameter__name,
          body.swagger-dark-mode .swagger-ui .parameter__type,
          body.swagger-dark-mode .swagger-ui .parameters-col_description,
          body.swagger-dark-mode .swagger-ui .response-col_status,
          body.swagger-dark-mode .swagger-ui .response-col_description,
          body.swagger-dark-mode .swagger-ui .model,
          body.swagger-dark-mode .swagger-ui .model-title,
          body.swagger-dark-mode .swagger-ui label,
          body.swagger-dark-mode .swagger-ui table thead tr td,
          body.swagger-dark-mode .swagger-ui table thead tr th {
            color: #e5e7eb !important;
          }

          body.swagger-dark-mode .swagger-ui .info .base-url,
          body.swagger-dark-mode .swagger-ui .info .title small,
          body.swagger-dark-mode .swagger-ui .tab li,
          body.swagger-dark-mode .swagger-ui .markdown p,
          body.swagger-dark-mode .swagger-ui .parameter__deprecated,
          body.swagger-dark-mode .swagger-ui .prop-format,
          body.swagger-dark-mode .swagger-ui .response-col_links {
            color: #94a3b8 !important;
          }

          body.swagger-dark-mode .swagger-ui .scheme-container,
          body.swagger-dark-mode .swagger-ui .opblock,
          body.swagger-dark-mode .swagger-ui .models,
          body.swagger-dark-mode .swagger-ui section.models,
          body.swagger-dark-mode .swagger-ui .dialog-ux .modal-ux,
          body.swagger-dark-mode .swagger-ui .auth-container,
          body.swagger-dark-mode .swagger-ui .responses-inner,
          body.swagger-dark-mode .swagger-ui .opblock-body pre,
          body.swagger-dark-mode .swagger-ui textarea,
          body.swagger-dark-mode .swagger-ui input,
          body.swagger-dark-mode .swagger-ui select {
            background: #111827 !important;
            border-color: #334155 !important;
            color: #e5e7eb !important;
          }

          body.swagger-dark-mode .swagger-ui .opblock .opblock-section-header,
          body.swagger-dark-mode .swagger-ui .models h4,
          body.swagger-dark-mode .swagger-ui section.models h4,
          body.swagger-dark-mode .swagger-ui .model-box,
          body.swagger-dark-mode .swagger-ui table tbody tr td {
            background: #1f2937 !important;
            border-color: #334155 !important;
          }

          body.swagger-dark-mode .swagger-ui .opblock.opblock-get,
          body.swagger-dark-mode .swagger-ui .opblock.opblock-post,
          body.swagger-dark-mode .swagger-ui .opblock.opblock-put,
          body.swagger-dark-mode .swagger-ui .opblock.opblock-delete,
          body.swagger-dark-mode .swagger-ui .opblock.opblock-patch {
            background: rgba(15, 23, 42, 0.82) !important;
          }

          body.swagger-dark-mode .swagger-ui .opblock-tag,
          body.swagger-dark-mode .swagger-ui .models {
            border-color: #334155 !important;
          }

          body.swagger-dark-mode .swagger-ui a,
          body.swagger-dark-mode .swagger-ui .opblock-summary-path a,
          body.swagger-dark-mode .swagger-ui .model-toggle::after {
            color: #38bdf8 !important;
          }

          body.swagger-dark-mode .swagger-ui .btn,
          body.swagger-dark-mode .swagger-ui .btn.authorize,
          body.swagger-dark-mode .swagger-ui button {
            background: #0b1120 !important;
            border-color: #334155 !important;
            color: #e5e7eb !important;
          }

          body.swagger-dark-mode .swagger-ui .highlight-code,
          body.swagger-dark-mode .swagger-ui .microlight {
            background: #020617 !important;
            color: #d1d5db !important;
          }

          .swagger-theme-toggle {
            align-items: center;
            background: #ffffff;
            border: 1px solid #94a3b8;
            border-radius: 4px;
            color: #0f172a;
            cursor: pointer;
            display: inline-flex;
            font: 600 13px/1 sans-serif;
            gap: 6px;
            height: 34px;
            margin-left: 12px;
            padding: 0 12px;
          }

          body.swagger-dark-mode .swagger-theme-toggle {
            background: #0b1120;
            border-color: #38bdf8;
            color: #e5e7eb;
          }
        </style>
        <script>
          (function () {
            var storageKey = 'swagger-ui-theme';
            function preferredTheme() {
              var stored = localStorage.getItem(storageKey);
              if (stored === 'dark' || stored === 'light') return stored;
              return window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
            }
            function applyTheme(theme) {
              var dark = theme === 'dark';
              document.documentElement.classList.toggle('swagger-dark-mode', dark);
              if (document.body) document.body.classList.toggle('swagger-dark-mode', dark);
              var toggle = document.getElementById('swagger-theme-toggle');
              if (toggle) {
                toggle.textContent = dark ? 'Light mode' : 'Dark mode';
                toggle.setAttribute('aria-pressed', String(dark));
              }
            }
            function installToggle(attempt) {
              var topbar = document.querySelector('.swagger-ui .topbar .wrapper') || document.querySelector('.swagger-ui .topbar');
              if (!topbar) {
                if (attempt < 20) window.setTimeout(function () { installToggle(attempt + 1); }, 150);
                return;
              }
              if (document.getElementById('swagger-theme-toggle')) return;
              var button = document.createElement('button');
              button.id = 'swagger-theme-toggle';
              button.className = 'swagger-theme-toggle';
              button.type = 'button';
              button.addEventListener('click', function () {
                var next = document.body.classList.contains('swagger-dark-mode') ? 'light' : 'dark';
                localStorage.setItem(storageKey, next);
                applyTheme(next);
              });
              topbar.appendChild(button);
              applyTheme(preferredTheme());
            }
            applyTheme(preferredTheme());
            document.addEventListener('DOMContentLoaded', function () {
              applyTheme(preferredTheme());
              installToggle(0);
            });
          }());
        </script>
        """;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        return !(path.endsWith("/swagger-ui.html") || path.endsWith("/swagger-ui/index.html"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        BufferedHtmlResponse wrapper = new BufferedHtmlResponse(response);
        filterChain.doFilter(request, wrapper);

        byte[] body = wrapper.body();
        String contentType = wrapper.getContentType();
        if (response.isCommitted()
            || wrapper.getStatus() >= 300
            || contentType == null
            || !contentType.toLowerCase().contains("text/html")) {
            response.getOutputStream().write(body);
            return;
        }

        Charset charset = charset(contentType);
        String html = new String(body, charset);
        String darkHtml = html.contains("swagger-theme-toggle")
            ? html
            : html.replace("</head>", THEME_ASSETS + "</head>");
        byte[] darkBody = darkHtml.getBytes(charset);
        response.setHeader(HttpHeaders.CONTENT_LENGTH, String.valueOf(darkBody.length));
        response.getOutputStream().write(darkBody);
    }

    private Charset charset(String contentType) {
        for (String part : contentType.split(";")) {
            String trimmed = part.trim();
            if (trimmed.toLowerCase().startsWith("charset=")) {
                return Charset.forName(trimmed.substring("charset=".length()));
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static final class BufferedHtmlResponse extends HttpServletResponseWrapper {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private ServletOutputStream outputStream;
        private PrintWriter writer;

        private BufferedHtmlResponse(HttpServletResponse response) {
            super(response);
        }

        @Override
        public ServletOutputStream getOutputStream() {
            if (outputStream == null) {
                outputStream = new ServletOutputStream() {
                    @Override
                    public boolean isReady() {
                        return true;
                    }

                    @Override
                    public void setWriteListener(WriteListener writeListener) {
                    }

                    @Override
                    public void write(int value) {
                        buffer.write(value);
                    }
                };
            }
            return outputStream;
        }

        @Override
        public PrintWriter getWriter() throws IOException {
            if (writer == null) {
                String encoding = getCharacterEncoding() == null ? StandardCharsets.UTF_8.name() : getCharacterEncoding();
                writer = new PrintWriter(new OutputStreamWriter(buffer, encoding));
            }
            return writer;
        }

        private byte[] body() {
            if (writer != null) {
                writer.flush();
            }
            return buffer.toByteArray();
        }
    }
}

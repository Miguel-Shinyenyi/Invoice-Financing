package com.settlementengine.core.lab;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;

/**
 * Applies a load run's fault profile to the loopback requests the runner sends for it. Loopback HTTP means the
 * fault cannot ride a ThreadLocal set by the lab endpoint, so the runner tags its requests with the run id. The
 * tag is honoured only from a loopback address and only for the one active run, and the profile it selects was
 * validated against the caps when the run started.
 */
@LabComponent
public class LabRunFaultFilter extends OncePerRequestFilter {

    private final LabLoadService loadService;

    public LabRunFaultFilter(LabLoadService loadService) {
        this.loadService = loadService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getHeader(LabLoadDriver.RUN_HEADER) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        LabFaultProfile profile = isLoopback(request) ? loadService.faultProfileFor(request.getHeader(LabLoadDriver.RUN_HEADER)) : null;
        if (profile == null) {
            chain.doFilter(request, response);
            return;
        }
        LabFaultContext.set(LabFaultPlan.profile(profile));
        try {
            chain.doFilter(request, response);
        } finally {
            LabFaultContext.clear();
        }
    }

    private static boolean isLoopback(HttpServletRequest request) {
        try {
            return InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress();
        } catch (Exception e) {
            return false;
        }
    }
}

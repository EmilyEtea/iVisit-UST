package com.ivisit.backend.controller;

import com.ivisit.backend.dto.RfidScanRequest;
import com.ivisit.backend.model.VisitorPass;
import com.ivisit.backend.service.VisitorPassService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/helper")
public class HelperRfidController {

    private static final Logger log = LoggerFactory.getLogger(HelperRfidController.class);

    @Autowired
    private VisitorPassService visitorPassService;

    @PostMapping("/rfid-scan")
    public ResponseEntity<?> handleRfidScan(@RequestBody RfidScanRequest request) {
        log.info("RFID scan received: uid={}, stationId={}, scannedAt={}",
                request.getUid(), request.getStationId(), request.getScannedAt());

        Optional<VisitorPass> passOpt = visitorPassService.findByUid(request.getUid());

        Map<String, Object> response = new HashMap<>();
        response.put("uid", request.getUid());
        response.put("stationId", request.getStationId());
        response.put("scannedAt", request.getScannedAt());
        response.put("passFound", passOpt.isPresent());

        if (passOpt.isPresent()) {
            VisitorPass pass = passOpt.get();
            response.put("passId", pass.getPassID());
            response.put("passNumber", pass.getPassNumber());
            response.put("passStatus", pass.getStatus());
            response.put("displayCode", pass.getDisplayCode());
            log.info("RFID pass resolved: passId={}, status={}, displayCode={}",
                    pass.getPassID(), pass.getStatus(), pass.getDisplayCode());
        } else {
            log.warn("RFID scan: no pass found for uid={}", request.getUid());
        }

        return ResponseEntity.ok(response);
    }
}

package com.example.laos.controller;

import com.example.laos.service.CommonService;
import com.example.laos.util.CreateInputDataFile;
import com.example.laos.vo.TankBasicInputData;
import com.example.laos.vo.TankInputData;
import com.example.laos.vo.TankResultData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@Slf4j
@RequiredArgsConstructor
public class ViewController {

    private final CommonService commonService;

    @GetMapping("/download/{selectedValue}")
    public ResponseEntity<byte[]> downloadFile(
            @PathVariable(value="selectedValue", required = true) String code
    ) {

        Map<String, Object> data = commonService.getTankInputData(code);
//        Map<String, ArrayList<Map<String,String>>> data = commonService.getTankInputData(code);
        CreateInputDataFile.dataToFile(code, (TankBasicInputData) data.get("basicData"), (ArrayList<TankInputData>) data.get("inputData"));

        // 생성한 파일을 읽어서 byte 배열로 변환
        byte[] fileBytes;
        try {
            fileBytes = Files.readAllBytes(Paths.get(code));
//            fileBytes = Files.readAllBytes(Paths.get("D:\\dev_etc\\tank\\"+code));
        } catch (IOException e) {
            e.printStackTrace();
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }


        // 실행할 모델의 경로
        String modelPath = "D:\\dev_etc\\tank\\SMTankSim.exe";

        try {
            // 모델 실행
            ProcessBuilder builder = new ProcessBuilder("cmd.exe", "/c", modelPath);
            builder.directory(new File("D:\\dev_etc\\tank\\"));
            builder.redirectError(ProcessBuilder.Redirect.INHERIT);
            //builder.redirectOutput(ProcessBuilder.Redirect.INHERIT);

            Process proc = builder.start();

            PrintWriter writer=new PrintWriter(proc.getOutputStream());
            writer.write(code+"\n");
            writer.flush();
            writer.close();

            int procResult = proc.waitFor();

            if(procResult == 0) {
                System.out.println("S");
            } else {
                System.out.println("F");
            }

//            // 모델 실행이 완료될 때까지 대기 (필요에 따라 적절한 대기 시간 설정)
//            if (!proc.waitFor(1, TimeUnit.MINUTES)) {
//                // 실행이 1분 안에 완료되지 않으면 timeout 처리
//                proc.destroy();
//                throw new RuntimeException("Model execution timeout.");
//            }
//
//            // 모델 실행이 성공적으로 종료되었는지 확인
////            int procResult = proc.exitValue();
//            if (procResult == 0) {
//                System.out.println("Model execution successful.");
//            } else {
//                System.out.println("Model execution failed with exit code: " + procResult);
//            }



            // 결과 파일을 읽어와서 데이터베이스에 저장
            ArrayList<TankResultData> simulationData = readSimulationReport("D:\\dev_etc\\",code);
            // DB에 저장하는 로직 (dao.insertTnTankBasinSimulationResults(simulationData) 등의 메서드 활용)


        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            // 모델 실행 중 오류가 발생하면 적절한 예외 처리
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        // 파일 다운로드를 위한 HTTP 응답 헤더 설정
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        headers.setContentDisposition(ContentDisposition.builder("attachment").filename(code).build()); // 확장자를 제거합니다.

        headers.setContentDispositionFormData("attachment", code);
//
////        return null;
        return new ResponseEntity<>(fileBytes, headers, HttpStatus.OK);


    }



    public ArrayList<TankResultData> readSimulationReport(String filePath,String code) throws IOException {
//        Map<String, Map<String, Double>> simulationData = new HashMap<>();
//        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
        try {
//            Path path = Paths.get(FILE_ROOT_DIR2, "모의", "dc0790.out");
            Path path = Paths.get(filePath, "tank", code+".out");
            List<String> lines = Files.readAllLines(path);

            int idx = 25;
            int cnt = 0;

            ArrayList<TankResultData> arr = new ArrayList<>();
            String uuid = UUID.randomUUID().toString();

//            while ((line = br.readLine()) != null) {
            while (idx < lines.size()) {
                String[] ls = lines.get(idx).replaceAll("\\s\\s+", "Q").split("Q");

                TankResultData vo = new TankResultData();
                vo.setResultId(uuid);
                vo.setObsDate(ls[0].replaceAll("\\s+", ""));
                vo.setR(ls[1]);
                vo.setQoCms(ls[2]);
                vo.setQsCms(ls[3]);
                vo.setQoMm(ls[4]);
                vo.setQsMm(ls[5]);
                vo.setXs(ls[6]);
                vo.setXa(ls[7]);
                vo.setXb(ls[8]);
                vo.setXc(ls[9]);
                vo.setXd(ls[10]);
                vo.setEt(ls[11]);

                arr.add(vo);

                idx++;
                cnt++;

                if (cnt % 1000 == 0) {
                    commonService.insertTankSimulationResults(arr);
                    arr.clear();
                }

                if (idx < lines.size() && lines.get(idx).contains("----")) {
                    idx += 2;
                    break;
                }
            }
            commonService.insertTankSimulationResults(arr);

            TankResultData trd = new TankResultData();

            trd.setResultId(uuid);
            trd.setSumRainfall(String.valueOf(BigDecimal.valueOf(Double.valueOf(lines.get(idx).trim().split("=")[1]))));
            trd.setSumObsFlowDpth(String.valueOf(BigDecimal.valueOf(Double.valueOf(lines.get(idx + 1).trim().split("=")[1]))));
            trd.setSumCompFlowDpth(String.valueOf(BigDecimal.valueOf(Double.valueOf(lines.get(idx + 2).trim().split("=")[1]))));
            trd.setSumEvaport(String.valueOf(BigDecimal.valueOf(Double.valueOf(lines.get(idx + 3).trim().split("=")[1]))));
            trd.setRunoffRatio(String.valueOf(BigDecimal.valueOf(Double.valueOf(lines.get(idx + 4).trim().split("=")[1]))));

            idx += 31;
            trd.setObsMean(String.valueOf(BigDecimal.valueOf(Double.valueOf(lines.get(idx).trim().split("=")[1]))));
            trd.setObsSdev(String.valueOf(BigDecimal.valueOf(Double.valueOf(lines.get(idx + 1).trim().split("=")[1]))));
            trd.setSimMean(String.valueOf(BigDecimal.valueOf(Double.valueOf(lines.get(idx + 2).trim().split("=")[1]))));
            trd.setSimSdev(String.valueOf(BigDecimal.valueOf(Double.valueOf(lines.get(idx + 3).trim().split("=")[1]))));

            commonService.insertTnTankBasinResults(trd); // insert

        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return null;
    }

}

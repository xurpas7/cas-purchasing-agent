package org.rmj.purchasing.agent;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.sql.ResultSet;
import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JRResultSetDataSource;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.data.JsonDataSource;
import net.sf.jasperreports.view.JasperViewer;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.rmj.appdriver.constants.EditMode;
import org.rmj.appdriver.constants.RecordStatus;
import org.rmj.appdriver.GRider;
import org.rmj.appdriver.iface.XMRecord;
import org.rmj.appdriver.MiscUtil;
import org.rmj.appdriver.SQLUtil;
import org.rmj.appdriver.agentfx.ShowMessageFX;
import org.rmj.appdriver.agentfx.ui.showFXDialog;
import org.rmj.cas.client.base.XMClient;
import org.rmj.cas.inventory.base.Inventory;
import org.rmj.cas.parameter.agent.XMBranch;
import org.rmj.cas.parameter.agent.XMDepartment;
import org.rmj.cas.parameter.agent.XMInventoryType;
import org.rmj.cas.parameter.agent.XMSupplier;
import org.rmj.cas.parameter.agent.XMTerm;
import org.rmj.cas.purchasing.base.POReceiving;
import org.rmj.cas.purchasing.pojo.UnitPOReceivingDetail;
import org.rmj.cas.purchasing.pojo.UnitPOReceivingMaster;
import org.rmj.appdriver.agentfx.callback.IMasterDetail;

public class XMPOReceiving implements XMRecord{
    public XMPOReceiving(GRider foGRider, String fsBranchCD, boolean fbWithParent){
        this.poGRider = foGRider;
        if (foGRider != null){
            this.pbWithParent = fbWithParent;
            this.psBranchCd = fsBranchCD;
            
            poControl = new POReceiving();
            poControl.setGRider(foGRider);
            poControl.setBranch(fsBranchCD);
            poControl.setWithParent(fbWithParent);
            
            pnEditMode = EditMode.UNKNOWN;
        }
    }
    
    @Override
    public void setMaster(int fnCol, Object foData) {
        if (pnEditMode != EditMode.UNKNOWN){
            // Don't allow specific fields to assign values
            if(!(fnCol == poData.getColumn("sTransNox") ||
                fnCol == poData.getColumn("cTranStat") ||
                fnCol == poData.getColumn("sModified") ||
                fnCol == poData.getColumn("dModified"))){
                
                poData.setValue(fnCol, foData);
                MasterRetreived(fnCol);
                
                if (fnCol == poData.getColumn("nDiscount") ||
                    fnCol == poData.getColumn("nAddDiscx") ||
                    fnCol == poData.getColumn("nFreightx")){
                    poData.setTranTotal(computeTotal());
                    poData.setTaxWHeld(computeTaxWHeld());
                    
                    MasterRetreived(11);
                    MasterRetreived(13);
                }
            }
        }
    }

    @Override
    public void setMaster(String fsCol, Object foData) {
        setMaster(poData.getColumn(fsCol), foData);
    }

    @Override
    public Object getMaster(int fnCol) {
        if(pnEditMode == EditMode.UNKNOWN || poControl == null)
         return null;
      else{
         return poData.getValue(fnCol);
      }
    }

    @Override
    public Object getMaster(String fsCol) {
        return getMaster(poData.getColumn(fsCol));
    }

    @Override
    public boolean newRecord() {
        poData = poControl.newTransaction();              
        
        if (poData == null){
            ShowMessageFX();
            return false;
        }else{
            poData.setDateTransact(poGRider.getServerDate());
            poData.setReferDate(poGRider.getServerDate());
            poData.setVATRate(pxeTaxRate);
            
            addDetail();
            pnEditMode = EditMode.ADDNEW;
            return true;
        }
    }

    @Override
    public boolean openRecord(String fstransNox) {
        poData = poControl.loadTransaction(fstransNox);
        
        if (poData.getTransNox()== null){
            ShowMessageFX();
            return false;
        } else{
            pnEditMode = EditMode.READY;
            return true;
        }
    }

    @Override
    public boolean updateRecord() {
        if(pnEditMode != EditMode.READY) {
         return false;
      }
      else{
         pnEditMode = EditMode.UPDATE;
         return true;
      }
    }

    @Override
    public boolean saveRecord() {
        if(pnEditMode == EditMode.UNKNOWN){
            return false;
        }else{                     
            poData.setTranTotal(computeTotal());
            poData.setTaxWHeld(computeTaxWHeld());
            // Perform testing on values that needs approval here...
            UnitPOReceivingMaster loResult;
            if(pnEditMode == EditMode.ADDNEW)
                loResult = poControl.saveUpdate(poData, "");
            else loResult = poControl.saveUpdate(poData, (String) poData.getValue(1));

            if(loResult == null){
                ShowMessageFX();
                return false;
            }else{
                pnEditMode = EditMode.READY;
                poData = loResult;
                return true;
            }
      }
    }

    @Override
    public boolean deleteRecord(String fsTransNox) {
        if(pnEditMode != EditMode.READY){
            return false;
        } else{
            boolean lbResult = poControl.deleteTransaction(fsTransNox);
            if (lbResult)
                pnEditMode = EditMode.UNKNOWN;
            else ShowMessageFX();

            return lbResult;
        }
    }

    @Override
    public boolean deactivateRecord(String fsTransNox) {
        return false;
    }

    @Override
    public boolean activateRecord(String fsTransNox) {
        return false;
    }

    @Override
    public void setBranch(String foBranchCD) {
        psBranchCd = foBranchCD;
    }

    @Override
    public int getEditMode() {
        return pnEditMode;
    }
    
    //Added methods
    public void setGRider(GRider foGrider){
        this.poGRider = foGrider;
        this.psUserIDxx = foGrider.getUserID();
        
        if (psBranchCd.isEmpty()) psBranchCd = poGRider.getBranchCode();
    }
    
    public boolean printRecord(){
        if (pnEditMode != EditMode.READY || poData == null){
            ShowMessageFX.Warning("Unable to print transaction.", "Warning", "No record loaded.");
            return false;
        }
        
        //Create the parameter
        Map<String, Object> params = new HashMap<>();
        params.put("sCompnyNm", "Guanzon Group");
        params.put("sBranchNm", poGRider.getBranchName());
        params.put("sAddressx", poGRider.getAddress() + ", " + poGRider.getTownName() + " " +poGRider.getProvince());
        params.put("sTransNox", poData.getTransNox());
        params.put("sReferNox", poData.getReferNo());
        params.put("dTransact", SQLUtil.dateFormat(poData.getDateTransact(), SQLUtil.FORMAT_LONG_DATE));
        params.put("dReferDte", SQLUtil.dateFormat(poData.getReferDate(), SQLUtil.FORMAT_LONG_DATE));
        params.put("sPrintdBy", poGRider.getUserID());
        
        JSONObject loJSON;
        JSONArray loArray = new JSONArray();
        
        String lsBarCodex;
        String lsDescript;
        Inventory loInventory = new Inventory(poGRider, psBranchCd, true);
        
        for (int lnCtr = 0; lnCtr <= poControl.ItemCount() -1; lnCtr ++){
            loInventory.BrowseRecord((String) poControl.getDetail(lnCtr, "sStockIDx"), true, false);
            lsBarCodex = (String) loInventory.getMaster("sBarCodex");
            lsDescript = (String) loInventory.getMaster("sDescript");
            
            loJSON = new JSONObject();
            loJSON.put("sField01", lsBarCodex);
            loJSON.put("sField02", lsDescript);
            loJSON.put("nField01", poControl.getDetail(lnCtr, "nQuantity"));
            loJSON.put("lField01", poControl.getDetail(lnCtr, "nUnitPrce"));
            loArray.add(loJSON);
        }
                 
        try {
            InputStream stream = new ByteArrayInputStream(loArray.toJSONString().getBytes("UTF-8"));
            JsonDataSource jrjson;
            
            jrjson = new JsonDataSource(stream);
            
            JasperPrint jrprint = JasperFillManager.fillReport(poGRider.getReportPath() + 
                                                                "PurchaseReceiving.jasper", params, jrjson);
        
            JasperViewer jv = new JasperViewer(jrprint, false);     
            jv.setVisible(true);
        } catch (JRException | UnsupportedEncodingException ex) {
            Logger.getLogger(XMPOReceiving.class.getName()).log(Level.SEVERE, null, ex);
            return false;
        }

        return true;
    }
    
    
    public boolean deleteDetail(int fnRow){
        boolean lbDel = poControl.deleteDetail(fnRow);
        
        if (lbDel){
            poData.setTranTotal(computeTotal());
            poData.setTaxWHeld(computeTaxWHeld());
            return true;
        } else return false;
    }
    
    public boolean cancelRecord(String fsTransNox){
        if(pnEditMode != EditMode.READY){
            return false;
        } else{
            boolean lbResult = poControl.cancelTransaction(fsTransNox);
            if (lbResult)
                pnEditMode = EditMode.UNKNOWN;
            else ShowMessageFX();

            return lbResult;
        }
    }
    
    public boolean closeRecord(String fsTransNox){
        if(pnEditMode != EditMode.READY){
            return false;
        } else{
            boolean lbResult = poControl.closeTransaction(fsTransNox, "na");
            if (lbResult)
                pnEditMode = EditMode.UNKNOWN;
            else ShowMessageFX();

            return lbResult;
        }
    }
    
    public boolean postRecord(String fsTransNox){
        if(pnEditMode != EditMode.READY){
            return false;
        } else{
            boolean lbResult = poControl.postTransaction(fsTransNox);
            if (lbResult)
                pnEditMode = EditMode.UNKNOWN;
            else ShowMessageFX();

            return lbResult;
        }
    }
    
    public boolean voidRecord(String fsTransNox){
        if(pnEditMode != EditMode.READY){
            return false;
        } else{
            boolean lbResult = poControl.voidTransaction(fsTransNox);
            if (lbResult)
                pnEditMode = EditMode.UNKNOWN;
            else ShowMessageFX();

            return lbResult;
        }
    }
    
    public boolean addDetail(){return poControl.addDetail();}
    public boolean addDetail(String fsOrderNox){
        addDetail();
        poControl.setDetail(poControl.ItemCount() -1, "sOrderNox", fsOrderNox);
        return true;
    }
    
    public int getDetailCount(){return poControl.ItemCount();}
    
    public void setDetail(int fnRow, int fnCol, Object foData){
        if (pnEditMode != EditMode.UNKNOWN){
            // Don't allow specific fields to assign values
            if(!(fnCol == poDetail.getColumn("sTransNox") ||
                fnCol == poDetail.getColumn("nEntryNox") ||
                fnCol == poDetail.getColumn("dModified"))){
                
                poControl.setDetail(fnRow, fnCol, foData);
                DetailRetreived(fnCol);
                
                if (fnCol == poDetail.getColumn("nQuantity") ||
                    fnCol == poDetail.getColumn("nUnitPrce") ||
                    fnCol == poDetail.getColumn("nFreightx")){
                    poData.setTranTotal(computeTotal());
                    poData.setTaxWHeld(computeTaxWHeld());
                    
                    MasterRetreived(11);
                    MasterRetreived(13);
                }
            }
        }
    }

    public void setDetail(int fnRow, String fsCol, Object foData){       
        setDetail(fnRow, poDetail.getColumn(fsCol), foData);
    }
    
    public Object getDetail(int fnRow, String fsCol){
        return poControl.getDetail(fnRow, fsCol);
    }   
    
    public Object getDetail(int fnRow, int fnCol){
        return poControl.getDetail(fnRow, fnCol);
    }

    private double computeTotal(){
        double lnTranTotal = 0;
        for (int lnCtr = 0; lnCtr <= poControl.ItemCount()-1; lnCtr ++){
            lnTranTotal += ((int) poControl.getDetail(lnCtr, "nQuantity") * Double.valueOf(poControl.getDetail(lnCtr, "nUnitPrce").toString())) 
                                + Double.valueOf(poControl.getDetail(lnCtr, "nFreightx").toString());
        }
        
        //add the freight charge to total order
        lnTranTotal += Double.valueOf(poData.getFreightCharge().toString());
        //less the discounts
        lnTranTotal = lnTranTotal - (lnTranTotal * Double.valueOf(poData.getDiscountRate().toString())) - Double.valueOf(poData.getAdditionalDisc().toString());
        return lnTranTotal;
    }
    
    private double computeTaxWHeld(){
        DecimalFormat df2 = new DecimalFormat(".##");
        String lsTaxWHeld = df2.format(((Double) poData.getTranTotal() / pxeTaxExcludRte) * pxeTaxWHeldRate);
        return Double.parseDouble(lsTaxWHeld);        
    }
    
    private void ShowMessageFX(){
        if (!poControl.getErrMsg().isEmpty()){
            if (!poControl.getMessage().isEmpty())
                ShowMessageFX.Error(poControl.getErrMsg(), pxeModuleName, poControl.getMessage());
            else ShowMessageFX.Error(poControl.getErrMsg(), pxeModuleName, null);
        }else ShowMessageFX.Information(null, pxeModuleName, poControl.getMessage());
    }
    
    public boolean SearchMaster(int fnCol, String fsValue, boolean fbByCode){

        switch(fnCol){
            case 2: //sBranchCd
                XMBranch loBranch = new XMBranch(poGRider, psBranchCd, true);
                if (loBranch.browseRecord(fsValue, fbByCode)){
                    setMaster(fnCol, (String) loBranch.getMaster("sBranchCd"));
                    setMaster(4, (String) loBranch.getMaster("sCompnyID"));
                    MasterRetreived(fnCol);
                    return true;
                }
                break;
            case 5: //sSupplier
                XMSupplier loSupplier = new XMSupplier(poGRider, psBranchCd, true);
                 
                if (loSupplier.browseRecord(fsValue, psBranchCd, fbByCode)){
                    setMaster(fnCol, loSupplier.getMaster("sClientID"));
                    MasterRetreived(fnCol);
                    return true;
                }
                break;
            case 8: //sTermCode
                XMTerm loTerm = new XMTerm(poGRider, psBranchCd, true);
                if (loTerm.browseRecord(fsValue, fbByCode)){
                    setMaster(fnCol, loTerm.getMaster("sTermCode"));
                    MasterRetreived(fnCol);
                    return true;
                }
                break;
            case 20: //sInvTypCd
                XMInventoryType loInv = new XMInventoryType(poGRider, psBranchCd, true);
                if (loInv.browseRecord(fsValue, fbByCode)){
                setMaster(fnCol, loInv.getMaster("sInvTypCd"));
                    MasterRetreived(fnCol);
                    return true;
                }
                break;
            case 29:
                XMDepartment loDept = new XMDepartment(poGRider, psBranchCd, true);
                if (loDept.browseRecord(fsValue, fbByCode)){
                    setMaster(fnCol, loDept.getMaster("sDeptIDxx"));
                    MasterRetreived(fnCol);
                    return true;
                }
                break;
        }
        
        return false;
    }
    
    public boolean SearchMaster(String fsCol, String fsValue, boolean fbByCode){
        return SearchMaster(poData.getColumn(fsCol), fsValue, fbByCode);
    }
    
    public JSONObject SearchDetail(int fnRow, int fnCol, String fsValue, boolean fbSearch, boolean fbByCode){
        String lsHeader = "";
        String lsColName = "";
        String lsColCrit = "";
        String lsSQL = "";
        JSONObject loJSON;
        ResultSet loRS;
        int lnRow;
        switch(fnCol){
            case 3:
                lsHeader = "Order No»Branch»Date»Total»Inv. Type»Supplier»Code";
                lsColName = "sTransNox»sBranchNm»dTransact»nTranTotl»xDescript»sClientNm»sInvTypCd";
                lsColCrit = "a.sTransNox»b.sBranchNm»a.dTransact»a.nTranTotl»c.sDescript»d.sClientNm»a.sInvTypCd";
                lsSQL = getSQ_Purchases();
                
                if (fbByCode){
                    lsSQL = MiscUtil.addCondition(lsSQL, "a.sTransNox = " + SQLUtil.toSQL(fsValue));
                
                    loRS = poGRider.executeQuery(lsSQL);
                    
                    loJSON = showFXDialog.jsonBrowse(poGRider, loRS, lsHeader, lsColName);
                }
                else {
                    loJSON = showFXDialog.jsonSearch(poGRider, 
                                                        lsSQL, 
                                                        fsValue, 
                                                        lsHeader, 
                                                        lsColName, 
                                                        lsColCrit, 
                                                        fbSearch ? 6 : 0);
                }
                
                if (loJSON != null){
                    setDetail(fnRow, fnCol, (String) loJSON.get("sTransNox"));
                    return loJSON;
                } else{
                    setDetail(fnRow, fnCol, "");
                    return null;
                }
            case 4:
            case 5:
                lsHeader = "Brand»Description»Unit»Model»Inv. Type»Barcode»Stock ID";
                lsColName = "xBrandNme»sDescript»sMeasurNm»xModelNme»xInvTypNm»sBarCodex»sStockIDx";
                lsColCrit = "b.sDescript»a.sDescript»f.sMeasurNm»c.sDescript»d.sDescript»a.sBarCodex»a.sStockIDx";
                
                if (getDetail(fnRow, "sOrderNox").equals(""))
                    lsSQL = MiscUtil.addCondition(getSQ_Inventory(), "a.cRecdStat = " + SQLUtil.toSQL(RecordStatus.ACTIVE));
                else 
                    lsSQL = MiscUtil.addCondition(getSQ_Stocks((String) getDetail(fnRow, "sOrderNox")), "a.cRecdStat = " + SQLUtil.toSQL(RecordStatus.ACTIVE));
                
                if (fbByCode){
                    lsSQL = MiscUtil.addCondition(lsSQL, "a.sStockIDx = " + SQLUtil.toSQL(fsValue));
                    
                    loRS = poGRider.executeQuery(lsSQL);
                    
                    loJSON = showFXDialog.jsonBrowse(poGRider, loRS, lsHeader, lsColName);
                }else {
                    loJSON = showFXDialog.jsonSearch(poGRider, 
                                                        lsSQL, 
                                                        fsValue, 
                                                        lsHeader, 
                                                        lsColName, 
                                                        lsColCrit, 
                                                        fbSearch ? 1 : 5);
                }
                                
                if (loJSON != null){
                    setDetail(fnRow, fnCol, (String) loJSON.get("sStockIDx"));
                    //delete the barcode and descript on temp table
                    setDetail(fnRow, 100, "");
                    setDetail(fnRow, 101, "");
                    
                    if (fnCol == 4)
                        setDetail(fnRow, "nUnitPrce", Double.valueOf((String)loJSON.get("nUnitPrce")));
                    
                    return loJSON;
                } else{
                    setDetail(fnRow, fnCol, "");
                    //delete the barcode and descript on temp table
                    setDetail(fnRow, 100, "");
                    setDetail(fnRow, 101, "");
                    
                    if (fnCol == 4)
                        setDetail(fnRow, "nUnitPrce", 0.00);
                    return null;
                }
            default:
                return null;
        }
    }
    
    public JSONObject SearchDetail(int fnRow, String fsCol, String fsValue, boolean fbSearch, boolean fbByCode){
        return SearchDetail(fnRow, poDetail.getColumn(fsCol), fsValue, fbSearch, fbByCode);
    }
    
    public Inventory GetInventory(String fsValue, boolean fbByCode, boolean fbSearch){        
        Inventory instance = new Inventory(poGRider, psBranchCd, fbSearch);
        instance.BrowseRecord(fsValue, fbByCode, false);
        return instance;
    }
    
    public XMTerm GetTerm(String fsValue, boolean fbByCode){
        if (fbByCode && fsValue.equals("")) return null;
        
        XMTerm instance  = new XMTerm(poGRider, psBranchCd, true);
        if (instance.browseRecord(fsValue, fbByCode))
            return instance;
        else
            return null;
    }
    
    public XMBranch GetBranch(String fsValue, boolean fbByCode){
        if (fbByCode && fsValue.equals("")) return null;
        
        XMBranch instance  = new XMBranch(poGRider, psBranchCd, true);
        if (instance.browseRecord(fsValue, fbByCode))
            return instance;
        else
            return null;
    }
    
    public JSONObject GetSupplier(String fsValue, boolean fbByCode){
        if (fbByCode && fsValue.equals("")) return null;
        
        XMClient instance  = new XMClient(poGRider, psBranchCd, true);
        return instance.SearchClient(fsValue, fbByCode);
    }
    
    public XMInventoryType GetInventoryType(String fsValue, boolean fbByCode){
        if (fbByCode && fsValue.equals("")) return null;
        
        XMInventoryType instance  = new XMInventoryType(poGRider, psBranchCd, true);
        if (instance.browseRecord(fsValue, fbByCode))
            return instance;
        else
            return null;
    }
    
    public XMDepartment GetDepartment(String fsValue, boolean fbByCode){
        if (fbByCode && fsValue.equals("")) return null;
        
        XMDepartment instance  = new XMDepartment(poGRider, psBranchCd, true);
        if (instance.browseRecord(fsValue, fbByCode))
            return instance;
        else
            return null;
    }
    
    public boolean BrowseRecord(String fsValue, boolean fbByCode){
        String lsHeader = "Refer Date»Supplier»Refer No»Inv. Type»Date»Trans No";
        String lsColName = "dRefernce»sClientNm»sReferNox»sDescript»dTransact»sTransNox";
        String lsColCrit = "a.dRefernce»d.sClientNm»a.sReferNox»c.sDescript»a.dTransact»a.sTransNox";
        String lsSQL = getSQ_POReceiving();
       
        JSONObject loJSON = showFXDialog.jsonSearch(poGRider, 
                                                    lsSQL, 
                                                    fsValue, 
                                                    lsHeader, 
                                                    lsColName, 
                                                    lsColCrit, 
                                                    fbByCode ? 2 : 1);
        
        if(loJSON == null)
            return false;
        else{
            return openRecord((String) loJSON.get("sTransNox"));
        } 
    }
    
    private String getSQ_Stocks(String fsOrderNox){
        return "SELECT " +
                    "  a.sStockIDx" +
                    ", a.sBarCodex" + 
                    ", a.sDescript" + 
                    ", a.sBriefDsc" + 
                    ", a.sAltBarCd" + 
                    ", a.sCategCd1" + 
                    ", a.sCategCd2" + 
                    ", a.sCategCd3" + 
                    ", a.sCategCd4" + 
                    ", a.sBrandCde" + 
                    ", a.sModelCde" + 
                    ", a.sColorCde" + 
                    ", a.sInvTypCd" + 
                    ", a.nUnitPrce" + 
                    ", a.nSelPrice" + 
                    ", a.nDiscLev1" + 
                    ", a.nDiscLev2" + 
                    ", a.nDiscLev3" + 
                    ", a.nDealrDsc" + 
                    ", a.cComboInv" + 
                    ", a.cWthPromo" + 
                    ", a.cSerialze" + 
                    ", a.cUnitType" + 
                    ", a.cInvStatx" + 
                    ", a.sSupersed" + 
                    ", a.cRecdStat" + 
                    ", b.sDescript xBrandNme" + 
                    ", c.sDescript xModelNme" + 
                    ", d.sDescript xInvTypNm" + 
                    ", f.sMeasurNm" +
                " FROM Inventory a" + 
                        " LEFT JOIN Brand b" + 
                            " ON a.sBrandCde = b.sBrandCde" + 
                        " LEFT JOIN Model c" + 
                            " ON a.sModelCde = c.sModelCde" + 
                        " LEFT JOIN Inv_Type d" + 
                            " ON a.sInvTypCd = d.sInvTypCd" + 
                        " LEFT JOIN Measure f" +
                            " ON a.sMeasurID = f.sMeasurID" +
                    ", Inv_Master e" + 
                    ", PO_Master g" +
                    ", PO_Detail h" +
                " WHERE a.sStockIDx = e.sStockIDx" + 
                    " AND g.sTransNox = h.sTransNox" +
                    " AND h.sStockIDx = e.sStockIDx" +
                    " AND g.sTransNox = " + SQLUtil.toSQL(fsOrderNox) +
                    " AND e.sBranchCd = " + SQLUtil.toSQL(psBranchCd);
    }
    
    private String getSQ_Inventory(){
        return "SELECT " +
                    "  a.sStockIDx" +
                    ", a.sBarCodex" + 
                    ", a.sDescript" + 
                    ", a.sBriefDsc" + 
                    ", a.sAltBarCd" + 
                    ", a.sCategCd1" + 
                    ", a.sCategCd2" + 
                    ", a.sCategCd3" + 
                    ", a.sCategCd4" + 
                    ", a.sBrandCde" + 
                    ", a.sModelCde" + 
                    ", a.sColorCde" + 
                    ", a.sInvTypCd" + 
                    ", a.nUnitPrce" + 
                    ", a.nSelPrice" + 
                    ", a.nDiscLev1" + 
                    ", a.nDiscLev2" + 
                    ", a.nDiscLev3" + 
                    ", a.nDealrDsc" + 
                    ", a.cComboInv" + 
                    ", a.cWthPromo" + 
                    ", a.cSerialze" + 
                    ", a.cUnitType" + 
                    ", a.cInvStatx" + 
                    ", a.sSupersed" + 
                    ", a.cRecdStat" + 
                    ", b.sDescript xBrandNme" + 
                    ", c.sDescript xModelNme" + 
                    ", d.sDescript xInvTypNm" +
                    ", f.sMeasurNm" +
                " FROM Inventory a" + 
                    " LEFT JOIN Brand b" + 
                        " ON a.sBrandCde = b.sBrandCde" + 
                    " LEFT JOIN Model c" + 
                        " ON a.sModelCde = c.sModelCde" + 
                    " LEFT JOIN Inv_Type d" + 
                        " ON a.sInvTypCd = d.sInvTypCd" +
                    " LEFT JOIN Measure f" +
                        " ON a.sMeasurID = f.sMeasurID";
    }
    
    private String getSQ_Purchases(){
        return "SELECT " +
                    "  a.sTransNox" +
                    ", a.sBranchCd" + 
                    ", a.dTransact" +
                    ", a.sInvTypCd" +
                    ", a.nTranTotl" + 
                    ", b.sBranchNm" + 
                    ", c.sDescript xDescript" + 
                    ", d.sClientNm" + 
                    ", a.cTranStat" + 
                    ", CASE " +
                        " WHEN a.cTranStat = '0' THEN 'OPEN'" +
                        " WHEN a.cTranStat = '1' THEN 'CLOSED'" +
                        " WHEN a.cTranStat = '2' THEN 'POSTED'" +
                        " WHEN a.cTranStat = '3' THEN 'CANCELLED'" +
                        " WHEN a.cTranStat = '4' THEN 'VOID'" +
                        " END AS xTranStat" +
                " FROM PO_Master a" + 
                            " LEFT JOIN Branch b" + 
                                " ON a.sBranchCd = b.sBranchCd" + 
                            " LEFT JOIN Inv_Type c" + 
                                " ON a.sInvTypCd = c.sInvTypCd" + 
                        ", Client_Master d" + 
                " WHERE a.sSupplier = d.sClientID" + 
                    " AND a.sBranchCd = " + SQLUtil.toSQL(poGRider.getBranchCode());
    }
    
    private String getSQ_POReceiving(){
        String lsTranStat = String.valueOf(pnTranStat);
        String lsCondition = "";
        if (lsTranStat.length() == 1) {
            lsCondition = "a.cTranStat = " + SQLUtil.toSQL(lsTranStat);
        } else {
            for (int lnCtr = 0; lnCtr <= lsTranStat.length() -1; lnCtr++){
                lsCondition = lsCondition + SQLUtil.toSQL(String.valueOf(lsTranStat.charAt(lnCtr))) + ",";
            }
            lsCondition = "(" + lsCondition.substring(0, lsCondition.length()-1) + ")";
            lsCondition = "a.cTranStat IN " + lsCondition;
        }
        
        return MiscUtil.addCondition("SELECT " +
                                        "  a.sTransNox" +
                                        ", a.sBranchCd" + 
                                        ", a.dTransact" +
                                        ", a.sInvTypCd" +
                                        ", a.nTranTotl" + 
                                        ", b.sBranchNm" + 
                                        ", c.sDescript" + 
                                        ", d.sClientNm" + 
                                        ", a.cTranStat" + 
                                        ", a.dRefernce" +
                                        ", a.sReferNox" + 
                                        ", CASE " +
                                            " WHEN a.cTranStat = '0' THEN 'OPEN'" +
                                            " WHEN a.cTranStat = '1' THEN 'CLOSED'" +
                                            " WHEN a.cTranStat = '2' THEN 'POSTED'" +
                                            " WHEN a.cTranStat = '3' THEN 'CANCELLED'" +
                                            " WHEN a.cTranStat = '4' THEN 'VOID'" +
                                            " END AS xTranStat" +
                                    " FROM PO_Receiving_Master a" + 
                                                " LEFT JOIN Branch b" + 
                                                    " ON a.sBranchCd = b.sBranchCd" + 
                                                " LEFT JOIN Inv_Type c" + 
                                                    " ON a.sInvTypCd = c.sInvTypCd" + 
                                            ", Client_Master d" + 
                                    " WHERE a.sSupplier = d.sClientID", lsCondition);
    }
    
    public void setTranStat(int fnValue){this.pnTranStat = fnValue;}
    
    //callback methods
    public void setCallBack(IMasterDetail foCallBack){
        poCallBack = foCallBack;
    }
    
    private void MasterRetreived(int fnRow){
        if (poCallBack == null) return;
        
        poCallBack.MasterRetreive(fnRow);
    }
    
    private void DetailRetreived(int fnRow){
        if (poCallBack == null) return;
        
        poCallBack.DetailRetreive(fnRow);
    }
    
    //Member Variables
    private GRider poGRider;
    private POReceiving poControl;
    private UnitPOReceivingMaster poData;
    private final UnitPOReceivingDetail poDetail = new UnitPOReceivingDetail();
    
    private String psBranchCd;
    private int pnEditMode;
    private String psUserIDxx;
    private boolean pbWithParent;
    private int pnTranStat = 0;
    private IMasterDetail poCallBack;
    
    private final String pxeModuleName = "XMPOReceiving";
    private final Double pxeTaxWHeldRate = 0.00; //0.01
    private final Double pxeTaxRate = 0.12;
    private final Double pxeTaxExcludRte = 1.12;
}

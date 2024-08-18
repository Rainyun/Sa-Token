/*
 * Copyright 2020-2099 sa-token.cc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cn.dev33.satoken.oauth2.data.generate;

import cn.dev33.satoken.oauth2.SaOAuth2Manager;
import cn.dev33.satoken.oauth2.consts.SaOAuth2Consts;
import cn.dev33.satoken.oauth2.dao.SaOAuth2Dao;
import cn.dev33.satoken.oauth2.data.convert.SaOAuth2DataConverter;
import cn.dev33.satoken.oauth2.data.model.*;
import cn.dev33.satoken.oauth2.data.model.request.RequestAuthModel;
import cn.dev33.satoken.oauth2.data.model.loader.SaClientModel;
import cn.dev33.satoken.oauth2.error.SaOAuth2ErrorCode;
import cn.dev33.satoken.oauth2.exception.SaOAuth2Exception;
import cn.dev33.satoken.oauth2.strategy.SaOAuth2Strategy;
import cn.dev33.satoken.util.SaFoxUtil;

import java.util.List;

/**
 * Sa-Token OAuth2 数据构建器，默认实现类
 *
 * @author click33
 * @since 1.39.0
 */
public class SaOAuth2DataGenerateDefaultImpl implements SaOAuth2DataGenerate {

    /**
     * 构建Model：Code授权码
     * @param ra 请求参数Model
     * @return 授权码Model
     */
    @Override
    public CodeModel generateCode(RequestAuthModel ra) {

        SaOAuth2Dao dao = SaOAuth2Manager.getDao();

        // 删除旧Code
        dao.deleteCode(dao.getCodeValue(ra.clientId, ra.loginId));

        // 生成新Code
        String codeValue = SaOAuth2Strategy.instance.createCodeValue.execute(ra.clientId, ra.loginId, ra.scopes);
        CodeModel cm = new CodeModel(codeValue, ra.clientId, ra.scopes, ra.loginId, ra.redirectUri);

        // 保存新Code
        dao.saveCode(cm);
        dao.saveCodeIndex(cm);

        // 返回
        return cm;
    }

    /**
     * 构建Model：Access-Token
     * @param code 授权码Model
     * @return AccessToken Model
     */
    @Override
    public AccessTokenModel generateAccessToken(String code) {

        SaOAuth2Dao dao = SaOAuth2Manager.getDao();
        SaOAuth2DataConverter dataConverter = SaOAuth2Manager.getDataConverter();

        // 1、先校验
        CodeModel cm = dao.getCode(code);
        SaOAuth2Exception.throwBy(cm == null, "无效code", SaOAuth2ErrorCode.CODE_30110);

        // 2、删除旧Token
        dao.deleteAccessToken(dao.getAccessTokenValue(cm.clientId, cm.loginId));
        dao.deleteRefreshToken(dao.getRefreshTokenValue(cm.clientId, cm.loginId));

        // 3、生成token
        AccessTokenModel at = dataConverter.convertCodeToAccessToken(cm);
        RefreshTokenModel rt = dataConverter.convertAccessTokenToRefreshToken(at);
        at.refreshToken = rt.refreshToken;
        at.refreshExpiresTime = rt.expiresTime;
        SaOAuth2Strategy.instance.workAccessTokenByScope.accept(at);

        // 4、保存token
        dao.saveAccessToken(at);
        dao.saveAccessTokenIndex(at);
        dao.saveRefreshToken(rt);
        dao.saveRefreshTokenIndex(rt);

        // 5、删除此Code
        dao.deleteCode(code);
        dao.deleteCodeIndex(cm.clientId, cm.loginId);

        // 6、返回 Access-Token
        return at;
    }

    /**
     * 刷新Model：根据 Refresh-Token 生成一个新的 Access-Token
     * @param refreshToken Refresh-Token值
     * @return 新的 Access-Token
     */
    @Override
    public AccessTokenModel refreshAccessToken(String refreshToken) {

        SaOAuth2Dao dao = SaOAuth2Manager.getDao();

        // 获取 Refresh-Token 信息
        RefreshTokenModel rt = dao.getRefreshToken(refreshToken);
        SaOAuth2Exception.throwBy(rt == null, "无效refresh_token: " + refreshToken, SaOAuth2ErrorCode.CODE_30111);

        // 如果配置了[每次刷新产生新的Refresh-Token]
        SaClientModel clientModel = SaOAuth2Manager.getDataLoader().getClientModelNotNull(rt.clientId);
        if(clientModel.getIsNewRefresh()) {
            // 删除旧 Refresh-Token
            dao.deleteRefreshToken(rt.refreshToken);

            // 创建并保持新的 Refresh-Token
            rt = SaOAuth2Manager.getDataConverter().convertRefreshTokenToRefreshToken(rt);
            dao.saveRefreshToken(rt);
            dao.saveRefreshTokenIndex(rt);
        }

        // 删除旧 Access-Token
        dao.deleteAccessToken(dao.getAccessTokenValue(rt.clientId, rt.loginId));

        // 生成新 Access-Token
        AccessTokenModel at = SaOAuth2Manager.getDataConverter().convertRefreshTokenToAccessToken(rt);

        // 保存新 Access-Token
        dao.saveAccessToken(at);
        dao.saveAccessTokenIndex(at);

        // 返回新 Access-Token
        return at;
    }

    /**
     * 构建Model：Access-Token (根据RequestAuthModel构建，用于隐藏式 and 密码式)
     * @param ra 请求参数Model
     * @param isCreateRt 是否生成对应的Refresh-Token
     * @return Access-Token Model
     */
    @Override
    public AccessTokenModel generateAccessToken(RequestAuthModel ra, boolean isCreateRt) {

        SaOAuth2Dao dao = SaOAuth2Manager.getDao();

        // 1、删除 旧Token
        dao.deleteAccessToken(dao.getAccessTokenValue(ra.clientId, ra.loginId));
        if(isCreateRt) {
            dao.deleteRefreshToken(dao.getRefreshTokenValue(ra.clientId, ra.loginId));
        }

        // 2、生成 新Access-Token
        String newAtValue = SaOAuth2Strategy.instance.createAccessToken.execute(ra.clientId, ra.loginId, ra.scopes);
        AccessTokenModel at = new AccessTokenModel(newAtValue, ra.clientId, ra.loginId, ra.scopes);
        // TODO 此处的 openid 应该怎么加载？
        // at.openid = SaOAuth2Manager.getDataLoader().getOpenid(ra.clientId, ra.loginId);
        SaClientModel clientModel = SaOAuth2Manager.getDataLoader().getClientModelNotNull(ra.clientId);
        at.expiresTime = System.currentTimeMillis() + (clientModel.getAccessTokenTimeout() * 1000);

        // 3、生成&保存 Refresh-Token
        if(isCreateRt) {
            RefreshTokenModel rt = SaOAuth2Manager.getDataConverter().convertAccessTokenToRefreshToken(at);
            dao.saveRefreshToken(rt);
            dao.saveRefreshTokenIndex(rt);
        }

        // 5、保存 新Access-Token
        dao.saveAccessToken(at);
        dao.saveAccessTokenIndex(at);

        // 6、返回 新Access-Token
        return at;
    }

    /**
     * 构建Model：Client-Token
     * @param clientId 应用id
     * @param scopes 授权范围
     * @return Client-Token Model
     */
    @Override
    public ClientTokenModel generateClientToken(String clientId, List<String> scopes) {

        SaOAuth2Dao dao = SaOAuth2Manager.getDao();

        // 1、删掉旧 Past-Token
        dao.deleteClientToken(dao.getPastTokenValue(clientId));

        // 2、将旧Client-Token 标记为新 Past-Token
        ClientTokenModel oldCt = dao.getClientToken(dao.getClientTokenValue(clientId));
        dao.savePastTokenIndex(oldCt);

        // 2.5、如果配置了 PastClientToken 的 ttl ，则需要更新一下
        SaClientModel cm = SaOAuth2Manager.getDataLoader().getClientModelNotNull(clientId);
        if(oldCt != null && cm.getPastClientTokenTimeout() != -1) {
            oldCt.expiresTime = System.currentTimeMillis() + (cm.getPastClientTokenTimeout() * 1000);
            dao.saveClientToken(oldCt);
        }

        // 3、生成新 Client-Token
        String clientTokenValue = SaOAuth2Strategy.instance.createClientToken.execute(clientId, scopes);
        ClientTokenModel ct = new ClientTokenModel(clientTokenValue, clientId, scopes);
        ct.expiresTime = System.currentTimeMillis() + (cm.getClientTokenTimeout() * 1000);
        SaOAuth2Strategy.instance.workClientTokenByScope.accept(ct);

        // 3、保存新Client-Token
        dao.saveClientToken(ct);
        dao.saveClientTokenIndex(ct);

        // 4、返回
        return ct;
    }

    /**
     * 构建URL：下放Code URL (Authorization Code 授权码)
     * @param redirectUri 下放地址
     * @param code code参数
     * @param state state参数
     * @return 构建完毕的URL
     */
    @Override
    public String buildRedirectUri(String redirectUri, String code, String state) {
        String url = SaFoxUtil.joinParam(redirectUri, SaOAuth2Consts.Param.code, code);
        if( ! SaFoxUtil.isEmpty(state)) {
            url = SaFoxUtil.joinParam(url, SaOAuth2Consts.Param.state, state);
        }
        return url;
    }

    /**
     * 构建URL：下放Access-Token URL （implicit 隐藏式）
     * @param redirectUri 下放地址
     * @param token token
     * @param state state参数
     * @return 构建完毕的URL
     */
    @Override
    public String buildImplicitRedirectUri(String redirectUri, String token, String state) {
        String url = SaFoxUtil.joinSharpParam(redirectUri, SaOAuth2Consts.Param.token, token);
        if( ! SaFoxUtil.isEmpty(state)) {
            url = SaFoxUtil.joinSharpParam(url, SaOAuth2Consts.Param.state, state);
        }
        return url;
    }

    /**
     * 回收 Access-Token
     * @param accessToken Access-Token值
     */
    @Override
    public void revokeAccessToken(String accessToken) {

        SaOAuth2Dao dao = SaOAuth2Manager.getDao();

        // 如果查不到任何东西, 直接返回
        AccessTokenModel at = dao.getAccessToken(accessToken);
        if(at == null) {
            return;
        }

        // 删除 Access-Token
        dao.deleteAccessToken(accessToken);
        dao.deleteAccessTokenIndex(at.clientId, at.loginId);

        // 删除对应的 Refresh-Token
        String refreshToken = dao.getRefreshTokenValue(at.clientId, at.loginId);
        dao.deleteRefreshToken(refreshToken);
        dao.deleteRefreshTokenIndex(at.clientId, at.loginId);
    }

}

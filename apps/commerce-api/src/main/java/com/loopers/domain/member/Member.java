package com.loopers.domain.member;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;

@Getter
@Entity
@Table(name = "member")
public class Member extends BaseEntity {

    @Column(name = "login_id", nullable = false, unique = true, length = 20)
    private String loginId;

    @Column(name = "password", nullable = false, length = 60)
    private String password;

    @Column(name = "name", nullable = false, length = 30)
    private String name;

    @Column(name = "email", nullable = false, unique = true, length = 100)
    private String email;

    @Column(name = "birth_date", nullable = false, length = 8)
    private String birthDate;

    protected Member() {}

    public Member(LoginId loginId, String encodedPassword, MemberName name, Email email, BirthDate birthDate) {
        this.loginId = loginId.value();
        this.password = encodedPassword;
        this.name = name.value();
        this.email = email.value();
        this.birthDate = birthDate.value();
    }

    public void changePassword(String encodedNewPassword) {
        this.password = encodedNewPassword;
    }
}

import http from 'k6/http';
import { sleep, check } from 'k6';

export let options = {
    stages: [
        { duration: '15s', target: 20 },
        { duration: '20s', target: 50 },
        { duration: '30s', target: 80 },
        { duration: '30s', target: 100 },  // 🔥 peak
        { duration: '15s', target: 0 },
    ],
    thresholds: {
        http_req_failed: ['rate<0.2'],     // relaxed for stress phase
        http_req_duration: ['p(95)<3000'],
    },
};

const BASE_URL = 'https://payment-service.wonderfulsky-f296b440.centralindia.azurecontainerapps.io';

// 🔥 Increase users to reduce contention
const users = Array.from({ length: 100 }, (_, i) => `User${i}`);

export default function () {

    let fromUser = users[Math.floor(Math.random() * users.length)];
    let toUser;
    do {
        toUser = users[Math.floor(Math.random() * users.length)];
    } while (toUser === fromUser);

    const uniqueId = `${__VU}-${__ITER}-${Date.now()}`;

    const useDuplicate = Math.random() < 0.05;
    const paymentKey = useDuplicate ? `dup-pay-${__VU}` : `pay-${uniqueId}`;
    const transferKey = useDuplicate ? `dup-transfer-${__VU}` : `transfer-${uniqueId}`;

    // 🔥 Only ONE operation per iteration (important at 100 VUs)
    if (__ITER % 2 === 0) {

        let paymentRes = http.post(
            `${BASE_URL}/api/v1/payments/process`,
            JSON.stringify({
                orderId: `ORDER-${uniqueId}`,
                amount: Math.floor(Math.random() * 100) + 1,
                currency: "USD",
                paymentMethodId: "pm_mock",
                idempotencyKey: paymentKey
            }),
            { headers: { 'Content-Type': 'application/json' } }
        );

        check(paymentRes, {
            'payment ok': (r) => [200, 202, 409].includes(r.status),
        });

    } else {

        let transferRes = http.post(
            `${BASE_URL}/api/v1/wallets/transfer`,
            JSON.stringify({
                fromUsername: fromUser,
                toUsername: toUser,
                amount: Math.floor(Math.random() * 10) + 1,
                idempotencyKey: transferKey
            }),
            { headers: { 'Content-Type': 'application/json' } }
        );

        check(transferRes, {
            'transfer ok': (r) => [200, 202, 409].includes(r.status),
        });
    }

    sleep(0.09 + Math.random() * 0.1);
}